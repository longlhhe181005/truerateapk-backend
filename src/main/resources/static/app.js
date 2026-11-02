(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var VND = new Intl.NumberFormat('vi-VN');
  var SCREEN_TAG = { 'screen-consent': 'Bước 1/3', 'screen-form': 'Bước 2/3', 'screen-analyzing': 'Bước 2/3', 'screen-result': 'Bước 3/3' };
  var SCREEN_TITLE = { 'screen-result': 'Kết quả phân tích' };
  var LEVEL_CLASS = { low: 'safe', caution: 'caution', high: 'caution', serious: 'danger', unknown: 'neutral' };

  // ---- screens (cross-fade: old out, then new in)
  function showScreen(id) {
    var next = $(id);
    var current = document.querySelector('.screen.active');
    $('stepTag').textContent = SCREEN_TAG[id] || '';
    $('deviceTitle').textContent = SCREEN_TITLE[id] || 'Kiểm tra khoản vay';
    if (current === next) { return; }
    function enter() {
      next.classList.add('active');
      void next.offsetWidth;
      requestAnimationFrame(function () { next.classList.add('in'); });
      var h = next.querySelector('h2');
      if (h) { h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true }); }
    }
    if (!current) { enter(); return; }
    current.classList.remove('in');
    setTimeout(function () { current.classList.remove('active'); enter(); }, 200);
  }

  // ---- status chip + OCR availability
  fetch('/api/health').then(function (r) { return r.json(); }).then(function (h) {
    var chip = $('aiChip');
    chip.hidden = false;
    chip.textContent = h.aiMode === 'online' ? 'AI giải thích: bật' : 'AI giải thích: chế độ mẫu';
    if (h.aiMode === 'online') { chip.classList.add('on'); }
    $('tabOcr').hidden = !h.ocr;
  }).catch(function () { /* page still works */ });

  // ---- consent
  $('consentBox').addEventListener('change', function () { $('consentNext').disabled = !this.checked; });
  $('consentNext').addEventListener('click', function () { showScreen('screen-form'); setTimeout(function () { $('lender').focus(); }, 450); });

  // ---- tabs
  function switchTab(tab) {
    var manual = tab === 'manual';
    $('tabManual').classList.toggle('active', manual);
    $('tabOcr').classList.toggle('active', !manual);
    $('ocrPanel').hidden = manual;
    $('loanForm').hidden = !manual;
  }
  $('tabManual').addEventListener('click', function () { switchTab('manual'); });
  $('tabOcr').addEventListener('click', function () { switchTab('ocr'); });

  // ---- money inputs: show dots while typing
  function digits(s) { return (s || '').replace(/\D+/g, ''); }
  // ---- fee lines (typed by the user or filled by the photo reader)
  function addFeeRow(name, amount) {
    if ($('feeList').children.length >= 8) { return; }
    var row = document.createElement('div');
    row.className = 'fee-row';
    var n = document.createElement('input'); n.type = 'text'; n.maxLength = 60; n.placeholder = 'Tên khoản phí'; n.className = 'fee-name'; n.value = name || '';
    var a = document.createElement('input'); a.type = 'text'; a.inputMode = 'numeric'; a.placeholder = 'Số tiền'; a.className = 'fee-amt';
    a.value = amount ? VND.format(amount) : '';
    a.addEventListener('input', function () { var d = digits(this.value); this.value = d ? VND.format(Number(d)) : ''; });
    var x = document.createElement('button'); x.type = 'button'; x.className = 'fee-del'; x.textContent = '×'; x.setAttribute('aria-label', 'Xóa khoản phí');
    x.addEventListener('click', function () { row.remove(); });
    row.appendChild(n); row.appendChild(a); row.appendChild(x);
    $('feeList').appendChild(row);
  }
  $('addFeeBtn').addEventListener('click', function () { addFeeRow('', null); });
  function readFees() {
    var out = [];
    document.querySelectorAll('#feeList .fee-row').forEach(function (r) {
      var nm = r.querySelector('.fee-name').value.trim(), am = Number(digits(r.querySelector('.fee-amt').value));
      if (nm && am) { out.push({ name: nm, amountVnd: am }); }
    });
    return out;
  }

  // ---- repayment type (one payment or equal monthly instalments)
  var payType = 'ONE_TIME';
  function setPayType(t) {
    payType = t;
    document.querySelectorAll('.seg-btn').forEach(function (b) {
      var on = b.getAttribute('data-pay') === t;
      b.classList.toggle('active', on);
      b.setAttribute('aria-checked', on ? 'true' : 'false');
    });
    var monthly = t === 'MONTHLY';
    $('installField').hidden = !monthly;
    $('installAmtField').hidden = !monthly;
    $('daysField').hidden = monthly;
    $('repayLabel').textContent = monthly ? 'Tổng số tiền phải trả (cộng tất cả các kỳ)' : 'Tổng số tiền phải trả';
  }
  document.querySelectorAll('.seg-btn').forEach(function (b) {
    b.addEventListener('click', function () { setPayType(b.getAttribute('data-pay')); });
  });
  function fillTotalFromInstalment() {
    var a = Number(digits($('installAmt').value)), n = Number(digits($('installments').value));
    if (a && n) { $('repay').value = VND.format(a * n); }
  }
  $('installAmt').addEventListener('input', fillTotalFromInstalment);
  $('installments').addEventListener('input', function () { this.value = digits(this.value).slice(0, 3); fillTotalFromInstalment(); });
  $('statedRate').addEventListener('input', function () { this.value = this.value.replace(/[^0-9.,]/g, '').slice(0, 7); });

  ['principal', 'repay', 'contractAmt', 'installAmt', 'income'].forEach(function (id) {
    $(id).addEventListener('input', function () {
      var d = digits(this.value);
      this.value = d ? VND.format(Number(d)) : '';
    });
  });
  $('days').addEventListener('input', function () { this.value = digits(this.value).slice(0, 4); });

  $('sampleBtn').addEventListener('click', function () {
    $('principal').value = VND.format(5000000);
    $('repay').value = VND.format(6150000);
    $('days').value = '28';
    $('lender').value = 'Vay Nhanh 247';
    $('contractAmt').value = '';
    clearErrors();
  });

  // ---- OCR: photos -> suggestions for the 4 fields (the user confirms)
  var usedOcr = false;
  var MAX_PAGES = 6;
  var picked = [];   // { blob, url }

  function resetOcr() {
    usedOcr = false;
    $('ocrConfirmRow').hidden = true;
    $('ocrConfirm').checked = false;
    $('ocrMsg').textContent = '';
    document.querySelectorAll('.field input.ai').forEach(function (i) { i.classList.remove('ai'); });
    picked.forEach(function (p) { URL.revokeObjectURL(p.url); });
    picked = [];
    $('ocrList').textContent = '';
    syncOcrButtons();
  }
  $('ocrBtn').addEventListener('click', function () { $('ocrFile').click(); });

  // Re-encode the photo in the browser: smaller upload, and EXIF/GPS metadata is dropped.
  function shrink(file) {
    return new Promise(function (resolve, reject) {
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.onload = function () {
        var max = 1600, w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, max / Math.max(w, h));
        var c = document.createElement('canvas');
        c.width = Math.round(w * k); c.height = Math.round(h * k);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        c.toBlob(function (b) { b ? resolve(b) : reject(new Error('encode')); }, 'image/jpeg', 0.85);
      };
      img.onerror = function () { URL.revokeObjectURL(url); reject(new Error('decode')); };
      img.src = url;
    });
  }

  function syncOcrButtons() {
    var ok = $('ocrConsent').checked;
    $('ocrBtn').disabled = !ok || picked.length >= MAX_PAGES;
    $('ocrSend').disabled = !ok || picked.length === 0;
    $('ocrSend').textContent = picked.length > 1 ? 'Đọc ' + picked.length + ' ảnh đã chọn' : 'Đọc ảnh đã chọn';
  }
  function renderPicked() {
    var ul = $('ocrList');
    ul.textContent = '';
    picked.forEach(function (p, i) {
      var li = document.createElement('li');
      var img = document.createElement('img');
      img.src = p.url; img.alt = 'Trang ' + (i + 1);
      var x = document.createElement('button');
      x.type = 'button'; x.textContent = '×'; x.setAttribute('aria-label', 'Bỏ trang ' + (i + 1));
      x.addEventListener('click', function () {
        URL.revokeObjectURL(p.url);
        picked.splice(i, 1);
        renderPicked();
      });
      var pg = document.createElement('div');
      pg.className = 'pg'; pg.textContent = 'Trang ' + (i + 1);
      li.appendChild(img); li.appendChild(x); li.appendChild(pg);
      ul.appendChild(li);
    });
    syncOcrButtons();
  }
  $('ocrConsent').addEventListener('change', syncOcrButtons);

  $('ocrFile').addEventListener('change', function () {
    var files = Array.prototype.slice.call(this.files || []);
    this.value = '';
    var room = MAX_PAGES - picked.length;
    if (files.length > room) {
      $('ocrMsg').textContent = 'Tối đa ' + MAX_PAGES + ' ảnh. Mình đã giữ ' + room + ' ảnh đầu.';
      files = files.slice(0, room);
    } else {
      $('ocrMsg').textContent = '';
    }
    Promise.all(files.map(function (f) {
      return shrink(f).then(function (blob) { return { blob: blob, url: URL.createObjectURL(blob) }; })
        .catch(function () { return null; });
    })).then(function (items) {
      var bad = items.filter(function (x) { return !x; }).length;
      items.forEach(function (x) { if (x) { picked.push(x); } });
      if (bad) { $('ocrMsg').textContent = bad + ' tệp không phải ảnh hợp lệ nên đã bị bỏ qua.'; }
      renderPicked();
    });
  });

  $('ocrSend').addEventListener('click', function () {
    if (!picked.length) { return; }
    var msg = $('ocrMsg');
    msg.textContent = 'Đang đọc ' + picked.length + ' ảnh…';
    $('ocrSend').disabled = true; $('ocrBtn').disabled = true;
    var fd = new FormData();
    picked.forEach(function (p, i) { fd.append('file', p.blob, 'page' + (i + 1) + '.jpg'); });
    fd.append('consent', 'true');
    fetch('/api/ocr', { method: 'POST', body: fd }).then(function (r) {
      return r.json().then(function (j) { return { ok: r.ok, body: j }; });
    }).then(function (res) {
      if (!res.ok) {
        msg.textContent = (res.body.errors && res.body.errors[0].message) || 'Không đọc được ảnh. Bạn có thể nhập tay.';
        return;
      }
      var d = res.body, read = [];
      if (d.principalVnd) { $('principal').value = VND.format(d.principalVnd); $('principal').classList.add('ai'); read.push('số tiền nhận'); }
      if (d.totalRepayVnd) { $('repay').value = VND.format(d.totalRepayVnd); $('repay').classList.add('ai'); read.push('tổng phải trả'); }
      if (d.termDays) { $('days').value = String(d.termDays); $('days').classList.add('ai'); read.push('số ngày'); }
      if (d.lenderName) { $('lender').value = d.lenderName; $('lender').classList.add('ai'); read.push('tên bên cho vay'); }
      if (typeof d.statedRatePercent === 'number') { $('statedRate').value = String(d.statedRatePercent).replace('.', ','); $('statedRate').classList.add('ai'); read.push('lãi suất hợp đồng ghi'); }
      if (d.installments && d.installmentVnd) {
        setPayType('MONTHLY');
        $('installments').value = String(d.installments); $('installments').classList.add('ai');
        $('installAmt').value = VND.format(d.installmentVnd); $('installAmt').classList.add('ai');
        $('repay').value = VND.format(d.installmentVnd * d.installments); $('repay').classList.add('ai');
        read.push('trả góp ' + d.installments + ' kỳ');
      }
      if (d.fees && d.fees.length) {
        $('feeList').textContent = '';
        d.fees.forEach(function (f) { if (f.amountVnd) { addFeeRow(f.name, f.amountVnd); } });
        if ($('feeList').children.length) { read.push('các khoản phí'); }
      }
      usedOcr = true;
      $('ocrConfirmRow').hidden = false;
      clearErrors();
      switchTab('manual');
      $('err-form').textContent = '';
      var clause = d.interestClause ? ' Điều khoản tính lãi đọc được: “' + d.interestClause + '”.' : '';
      var note = 'Đã đọc: ' + read.join(', ') + clause + '. Hãy kiểm tra từng ô (ô được tô vàng do AI đọc), sửa tay ô trống hoặc sai.' + (d.notes ? ' Lưu ý từ AI: ' + d.notes : '');
      $('ocrMsg').textContent = note;
      $('err-form').textContent = '';
      $('formNote').textContent = note;
      $('formNote').hidden = false;
    }).catch(function () {
      msg.textContent = 'Không xử lý được ảnh. Hãy thử lại hoặc nhập tay.';
    }).then(syncOcrButtons);
  });

  // ---- validation (mirrors the server; the server is the source of truth)
  function clearErrors() {
    document.querySelectorAll('.err').forEach(function (e) { e.textContent = ''; });
    document.querySelectorAll('.field input').forEach(function (i) { i.classList.remove('bad'); });
  }
  function setError(field, msg) {
    var map = { principalVnd: 'principal', totalRepayVnd: 'repay', termDays: 'days', lenderName: 'lender', contractAmountVnd: 'contractAmt', installments: 'installments', statedRatePercent: 'statedRate', monthlyIncomeVnd: 'income' };
    var el = $('err-' + field);
    if (el) { el.textContent = msg; }
    if (map[field]) { $(map[field]).classList.add('bad'); }
  }
  function readForm() {
    var p = digits($('principal').value), r = digits($('repay').value), d = digits($('days').value), c = digits($('contractAmt').value);
    var n = digits($('installments').value), sr = $('statedRate').value.trim().replace(',', '.');
    var monthly = payType === 'MONTHLY';
    return {
      repaymentType: payType,
      installments: monthly && n ? Number(n) : null,
      statedRatePercent: sr && !isNaN(Number(sr)) ? Number(sr) : null,
      fees: readFees(),
      monthlyIncomeVnd: digits($('income').value) ? Number(digits($('income').value)) : null,
      signals: Array.prototype.map.call(document.querySelectorAll('.sig:checked'), function (c) { return c.value; }),
      contractAmountVnd: c ? Number(c) : null,
      principalVnd: p ? Number(p) : null,
      totalRepayVnd: r ? Number(r) : null,
      termDays: !monthly && d ? Number(d) : null,
      lenderName: $('lender').value.trim(),
      consent: $('consentBox').checked
    };
  }
  function localCheck(v) {
    var errs = [];
    if (!v.principalVnd) errs.push(['principalVnd', 'Nhập số tiền bạn thực nhận.']);
    if (!v.totalRepayVnd) errs.push(['totalRepayVnd', 'Nhập tổng số tiền bạn phải trả.']);
    if (v.principalVnd && v.totalRepayVnd && v.totalRepayVnd <= v.principalVnd) {
      errs.push(['totalRepayVnd', 'Tổng tiền phải trả phải lớn hơn số tiền bạn thực nhận. Hãy kiểm tra xem bạn có nhập nhầm hai ô không.']);
    }
    if (v.repaymentType === 'MONTHLY') {
      if (!v.installments || v.installments < 2) errs.push(['installments', 'Nhập số kỳ trả góp (ít nhất 2 tháng).']);
    } else if (!v.termDays || v.termDays < 1) {
      errs.push(['termDays', 'Nhập số ngày vay (ít nhất 1 ngày).']);
    }
    if (!v.lenderName) errs.push(['lenderName', 'Nhập tên bên cho vay như ghi trên hợp đồng hoặc ứng dụng.']);
    return errs;
  }

  // ---- analyse
  var stepTimer = null;
  function startSteps() {
    var steps = ['Đang tính lãi suất thực…', 'Đối chiếu danh sách cấp phép…', 'So với mức tham khảo 20% và 100%…', 'Viết lời giải thích…'];
    var i = 0, el = $('analyzeStepText');
    el.textContent = steps[0];
    stepTimer = setInterval(function () { i = Math.min(i + 1, steps.length - 1); el.textContent = steps[i]; }, 600);
  }
  function stopSteps() { if (stepTimer) { clearInterval(stepTimer); stepTimer = null; } }

  $('loanForm').addEventListener('submit', function (ev) {
    ev.preventDefault();
    clearErrors();
    var v = readForm();
    var errs = localCheck(v);
    if (usedOcr && !$('ocrConfirm').checked) {
      setError('form', 'Hãy đối chiếu 4 ô với hợp đồng gốc và tick xác nhận trước khi xem kết quả.');
      return;
    }
    if (errs.length) {
      errs.forEach(function (e) { setError(e[0], e[1]); });
      return;
    }
    showScreen('screen-analyzing');
    startSteps();
    var started = Date.now();
    fetch('/api/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(v)
    }).then(function (r) {
      return r.json().then(function (j) { return { ok: r.ok, body: j }; });
    }).then(function (res) {
      var wait = Math.max(0, 1100 - (Date.now() - started));
      return new Promise(function (resolve) { setTimeout(function () { resolve(res); }, wait); });
    }).then(function (res) {
      stopSteps();
      if (!res.ok) {
        showScreen('screen-form');
        (res.body.errors || [{ field: 'form', message: 'Có lỗi xảy ra. Hãy thử lại.' }])
          .forEach(function (e) { setError(e.field, e.message); });
        return;
      }
      showScreen('screen-result');
      render(res.body);
    }).catch(function () {
      stopSteps();
      showScreen('screen-form');
      setError('form', 'Không kết nối được máy chủ. Hãy kiểm tra mạng và thử lại.');
    });
  });

  // ---- rendering (textContent only: lender name is user text)
  function money(n) { return VND.format(n) + 'đ'; }
  var LICENCE_TEXT = {
    LICENSED: 'Có trong danh sách cấp phép',
    NOT_FOUND: 'Không tìm thấy trong danh sách cấp phép',
    UNDETERMINED: 'Chưa xác định được tình trạng cấp phép'
  };
  function animateCount(el, to, finalText) {
    var start = performance.now(), dur = 1100;
    function tick(now) {
      var p = Math.max(0, Math.min((now - start) / dur, 1)), e = 1 - Math.pow(1 - p, 3);
      el.textContent = p < 1 ? VND.format(Math.round(to * e)) : finalText;
      if (p < 1) { requestAnimationFrame(tick); }
    }
    requestAnimationFrame(tick);
  }
  var lastD = null;
  function render(d) {
    lastD = d;
    var cls = LEVEL_CLASS[d.risk.level] || 'neutral';
    var color = 'var(--' + (cls === 'neutral' ? 'neutral' : cls) + ')';
    var badge = $('riskBadge');
    badge.className = 'badge ' + cls;
    $('riskTitle').textContent = d.risk.title;
    $('riskMsg').textContent = d.risk.message;
    $('rateNum').style.color = color;
    $('statusDot').style.background = color;
    var apr = d.aprCapped ? 1000000 : d.aprPercent;
    animateCount($('aprValue'), apr, d.aprCapped ? 'hơn 1.000.000' : VND.format(d.aprPercent));
    var monthly = d.repaymentType === 'MONTHLY';
    $('aprSub').textContent = monthly
      ? 'Trả ' + d.installments + ' kỳ, mỗi kỳ khoảng ' + money(d.installmentVnd) + '. Tổng trả thêm ' + money(d.extraCostVnd) + ' (' + VND.format(d.extraPercent) + '% số tiền nhận).'
      : 'Bạn trả thêm ' + money(d.extraCostVnd) + ' (' + VND.format(d.extraPercent) + '% số tiền nhận) trong ' + d.termDays + ' ngày.';
    var hasStated = typeof d.statedRatePercent === 'number';
    $('statedRow').hidden = !hasStated;
    if (hasStated) {
      $('statedVal').textContent = VND.format(d.statedRatePercent) + '%';
      var gapPts = Math.round((d.aprPercent - d.statedRatePercent) * 10) / 10;
      if (!d.aprCapped && gapPts >= 1) {
        $('aprSub').textContent += ' Lãi suất thật cao hơn lãi suất hợp đồng ghi khoảng ' + VND.format(gapPts) +
          ' điểm phần trăm, thường do phí hoặc bảo hiểm bị trừ hay cộng thêm.';
      }
      $('fillStated').style.width = '0%';
      requestAnimationFrame(function () { requestAnimationFrame(function () {
        $('fillStated').style.width = (Math.min(d.statedRatePercent, 320) / 320 * 100) + '%';
      }); });
    }
    var ST = {
      MATCH: ['Khớp', 'ok'],
      FEES_EXPLAIN: ['Khớp nếu tính các khoản phí bạn nhập vào gốc', 'ok'],
      HIDDEN: ['Chênh chưa giải thích được', 'bad'],
      LOWER: ['Hợp đồng thu ít hơn mức này', 'warn']
    };
    var lc = d.legalCap;
    $('capBox').hidden = !lc;
    if (lc) {
      var per = d.repaymentType === 'MONTHLY' ? 'cả kỳ vay ' + d.installments + ' tháng' : d.termDays + ' ngày';
      $('capText').textContent = 'So với mốc tham khảo 20%/năm trong Bộ luật Dân sự: với số tiền ' + money(d.principalVnd) + ' trong ' + per +
        ', mốc đó tương ứng khoảng ' + money(lc.cap20Vnd) + ' tiền lãi (' + VND.format(lc.capPercentOfPrincipal) + '% số tiền vay). ' +
        'Bạn phải trả thêm ' + money(lc.actualVnd) + (lc.multipleOf20 ? ', gấp khoảng ' + VND.format(lc.multipleOf20) + ' lần' : '') +
        '. Khoản trả thêm gồm cả phí, và việc mốc này có áp dụng cho bên cho vay của bạn hay không cần luật sư xác nhận.';
    }
    var sceBox = $('scenBox'), scTb = $('scenTable').querySelector('tbody');
    scTb.textContent = '';
    sceBox.hidden = !d.scenarios;
    if (d.scenarios) {
      d.scenarios.forEach(function (s) {
        var tr = document.createElement('tr');
        if (s.best) { tr.className = 'best'; }
        var c1 = document.createElement('td'); c1.className = 'l'; c1.textContent = s.title + (s.best ? ' (gần nhất)' : '');
        var c2 = document.createElement('td'); c2.textContent = money(s.impliedBasisVnd);
        var c3 = document.createElement('td'); c3.textContent = (s.gapVnd >= 0 ? '+' : '−') + money(Math.abs(s.gapVnd));
        var c4 = document.createElement('td'); c4.className = 'l scen-st ' + ST[s.status][1]; c4.textContent = ST[s.status][0];
        [c1, c2, c3, c4].forEach(function (c) { tr.appendChild(c); });
        scTb.appendChild(tr);
      });
      var bestS = d.scenarios.filter(function (s) { return s.best; })[0];
      $('scenSummary').textContent = bestS ? bestS.title + ' (' + ST[bestS.status][0].toLowerCase() + ')' : '';
      $('scenNote').textContent = 'Với lãi suất hợp đồng ghi ' + VND.format(d.statedRatePercent) + '%/năm, mỗi cách tính cho một số tiền gốc khác nhau. ' +
        'Gốc suy ra là số tiền lẽ ra bạn phải nhận để tổng phải trả đúng như vậy. So với số bạn thực nhận ' + money(d.principalVnd) +
        (d.listedFeesVnd ? ', cộng các khoản phí bạn đã nhập ' + money(d.listedFeesVnd) : '') + '.';
    }
    $('hiddenBox').hidden = !d.hiddenCostVnd;
    if (d.hiddenCostVnd) {
      $('hiddenVal').textContent = money(d.hiddenCostVnd);
      $('hiddenMsg').textContent = 'Phần này không giải thích được bằng lãi theo lãi suất hợp đồng ghi hay các khoản phí bạn đã nhập. ' +
        'Thường là phí hoặc bảo hiểm được cộng vào gốc. Hãy hỏi bên cho vay bằng văn bản đây là khoản gì, rồi thêm vào mục phí để tính lại.';
    }
    var sb = $('schedTable').querySelector('tbody');
    sb.textContent = '';
    $('scheduleBox').hidden = !monthly;
    if (monthly) {
      $('schedN').textContent = d.installments;
      d.schedule.forEach(function (r) {
        var tr = document.createElement('tr');
        [r.k, money(r.payment), money(r.interest), money(r.balance)].forEach(function (v) {
          var td = document.createElement('td'); td.textContent = v; tr.appendChild(td);
        });
        sb.appendChild(tr);
      });
    }
    $('fillLegal').style.width = '0%';
    $('fillReal').style.width = '0%';
    requestAnimationFrame(function () { requestAnimationFrame(function () {
      $('fillLegal').style.width = (20 / 320 * 100) + '%';
      $('fillReal').style.width = (Math.min(apr, 320) / 320 * 100) + '%';
    }); });
    $('fillRealVal').textContent = d.aprCapped ? '>1M%' : VND.format(d.aprPercent) + '%';

    // compound view: what the debt becomes if it is rolled over and interest is added to the principal
    $('compoundVal').textContent = d.compoundCapped ? 'hơn 1.000.000%/năm' : VND.format(d.compoundPercent) + '%/năm';
    $('compoundText').textContent = 'Con số trên là lãi đơn: bạn trả thêm ' + VND.format(d.extraPercent) + '% sau ' + d.termDays +
      ' ngày, nhân lên cho đủ một năm. Nếu cuối mỗi ' + d.termDays + ' ngày bạn không trả mà cộng lãi vào gốc rồi vay tiếp, nợ sẽ tăng như bảng dưới. ' +
      'Khoản vay của bạn chỉ kéo dài ' + d.termDays + ' ngày nên đây chỉ là minh họa, không phải số tiền bạn phải trả.';
    if (monthly) {
      $('compoundText').textContent = 'Con số chính là lãi suất mỗi tháng nhân 12, tính trên số nợ gốc còn lại sau từng kỳ. ' +
        'Nếu quy đổi theo kiểu lãi kép (lãi tháng trước cũng sinh lãi), lãi suất hiệu dụng là con số ở dòng tiêu đề.';
    }
    var tb = $('growTable').querySelector('tbody');
    tb.textContent = '';
    var ratio = d.totalRepayVnd / d.principalVnd, debt = d.principalVnd;
    $('growTable').hidden = monthly;
    for (var k = 1; !monthly && k * d.termDays <= 365 && k <= 12; k++) {
      debt = debt * ratio;
      if (k <= 3 || k === 6 || k === 9 || k === 12 || k * d.termDays > 330) {
        var tr = document.createElement('tr');
        var a = document.createElement('td'), b = document.createElement('td');
        a.textContent = k + ' kỳ (' + (k * d.termDays) + ' ngày)';
        b.textContent = money(Math.round(debt));
        tr.appendChild(a); tr.appendChild(b); tb.appendChild(tr);
      }
    }
    $('compoundBox').hidden = !monthly && !tb.children.length;

    var lic = LICENCE_TEXT[d.licence.status] || d.licence.status;
    if (d.licence.matchedName) {
      lic += d.licence.status === 'LICENSED' ? ' (' + d.licence.matchedName + ')' : ' · gần giống: ' + d.licence.matchedName;
    }
    $('fLicence').textContent = lic;
    $('legalIcon').textContent = d.licence.status === 'LICENSED' ? '✓' : '⚠';

    var gap = d.contractGap;
    $('gapBox').hidden = !gap;
    if (gap) {
      $('gapMsg').textContent = 'Hợp đồng ghi ' + money(gap.contractAmountVnd) + ', bạn nhận ' + money(d.principalVnd) +
        ' (ít hơn ' + money(gap.shortfallVnd) + ', khoảng ' + VND.format(gap.shortfallPercent) + '%). ' +
        'Phần chênh có thể là phí bảo hiểm hoặc phí khác trừ vào khoản vay, hoặc khoản vay bị giảm sau khi bạn đồng ý. ' +
        'Hãy hỏi bên cho vay bằng văn bản để họ giải thích từng khoản bị trừ, và giữ lại ảnh hợp đồng, màn hình giải ngân và sao kê.';
    }
    $('fLender').textContent = d.lenderName;
    $('fPrincipal').textContent = money(d.principalVnd);
    $('fRepay').textContent = money(d.totalRepayVnd);
    $('fExtra').textContent = money(d.extraCostVnd);
    $('fDays').textContent = d.repaymentType === 'MONTHLY' ? d.installments + ' tháng (' + d.installments + ' kỳ)' : d.termDays + ' ngày';
    $('explanation').textContent = d.explanation;
    $('legalComparison').textContent = d.legalComparison;
    $('nextStep').textContent = d.nextStepText;
    $('aiNote').textContent = d.ai.mode !== 'offline' ? 'Phần giải thích do AI viết từ các con số đã tính sẵn bằng code.' : (d.ai.note || '');
    // warning signs the user ticked
    $('signalBox').hidden = !(d.signals && d.signals.length);
    if (d.signals && d.signals.length) { $('signalText').textContent = d.signals.join('; ') + '.'; }

    // repayment burden against monthly income (30% and 50% are the team's reference assumptions, not official limits)
    $('burdenBox').hidden = !d.burden;
    if (d.burden) {
      var pct = d.burden.percent, cls = pct > 50 ? 'b-bad' : (pct > 30 ? 'b-warn' : 'b-ok');
      $('burdenPct').textContent = VND.format(pct) + '%';
      $('burdenLbl').textContent = d.burden.basis === 'MONTHLY'
        ? 'thu nhập mỗi tháng dành cho khoản trả góp ' + money(d.burden.paymentVnd)
        : 'thu nhập một tháng, nếu phải trả ' + money(d.burden.paymentVnd) + ' trong một lần';
      var f = $('burdenFill'); f.className = 'compare-fill ' + cls; f.style.width = '0%';
      requestAnimationFrame(function () { requestAnimationFrame(function () { f.style.width = Math.min(pct, 100) + '%'; }); });
      $('burdenNote').textContent = 'Mốc 30% và 50% chỉ là giả định tham khảo của nhóm, chưa phải ngưỡng chính thức. Chưa tính các khoản nợ khác của bạn.';
    }

    // late-payment illustration: the same simple rate keeps running on the amount due
    var lateRate = typeof d.statedRatePercent === 'number' ? d.statedRatePercent : d.aprPercent;
    var lb = $('lateTable').querySelector('tbody');
    lb.textContent = '';
    [10, 30, 60].forEach(function (days) {
      var add = Math.round(d.totalRepayVnd * lateRate / 100 * days / 365);
      var tr = document.createElement('tr');
      var a = document.createElement('td'); a.className = 'l'; a.textContent = days + ' ngày';
      var b = document.createElement('td'); b.textContent = money(d.totalRepayVnd + add);
      var c2 = document.createElement('td'); c2.textContent = '+' + money(add);
      tr.appendChild(a); tr.appendChild(b); tr.appendChild(c2); lb.appendChild(tr);
    });
    $('lateNote').textContent = 'Minh họa với lãi suất ' + VND.format(lateRate) + '%/năm tiếp tục tính trên số tiền phải trả (' +
      (typeof d.statedRatePercent === 'number' ? 'lãi suất hợp đồng ghi' : 'lãi suất quy năm tính được') +
      '). Hợp đồng thật có thể tính lãi quá hạn và phí phạt khác, hãy đọc điều khoản trả chậm.';

    // alternatives are shown when the rate is above the reference level or the lender is not verified
    $('altBox').hidden = !(d.aprPercent > 20 || d.licence.status !== 'LICENSED');

    // letter to the lender, filled with the user's own numbers
    var L = [];
    L.push('Kính gửi ' + d.lenderName + ',');
    L.push('');
    L.push('Tôi đang có khoản vay với các thông tin sau:');
    L.push('- Số tiền tôi thực nhận: ' + money(d.principalVnd));
    L.push('- Tổng số tiền tôi phải trả: ' + money(d.totalRepayVnd) + (d.repaymentType === 'MONTHLY' ? ' (' + d.installments + ' kỳ)' : ' sau ' + d.termDays + ' ngày'));
    if (typeof d.statedRatePercent === 'number') { L.push('- Lãi suất ghi trong hợp đồng: ' + VND.format(d.statedRatePercent) + '%/năm'); }
    if (d.contractGap) { L.push('- Số tiền hợp đồng ghi: ' + money(d.contractGap.contractAmountVnd) + ', khác số tôi thực nhận ' + money(d.principalVnd)); }
    if (d.hiddenCostVnd) { L.push('- Theo cách tính lãi ghi trong hợp đồng, có khoảng ' + money(d.hiddenCostVnd) + ' trong tổng phải trả mà tôi chưa thấy được giải thích.'); }
    L.push('');
    L.push('Tôi đề nghị quý đơn vị cung cấp bằng văn bản:');
    L.push('1. Chi tiết từng khoản phí tôi phải trả và điều khoản trong hợp đồng quy định các khoản đó.');
    L.push('2. Bảng tính lãi và lịch trả nợ chi tiết.');
    if (d.contractGap || d.hiddenCostVnd) { L.push('3. Lý do số tiền tôi nhận khác với số tiền ghi trong hợp đồng hoặc khác với kết quả tính lãi.'); }
    L.push('');
    L.push('Xin trân trọng cảm ơn.');
    L.push('[Họ tên và ngày]');
    fallbackLetter = L.join(String.fromCharCode(10));
    kitState = 'idle';
    $('kitSteps').textContent = '';
    $('letterText').hidden = true; $('copyLetter').hidden = true; $('kitFoot').hidden = true; $('copyOk').hidden = true;
    $('kitAiMsg').textContent = 'Mở mục này để AI soạn các bước và mẫu thư riêng cho khoản vay của bạn.';
    $('kitBox').open = false;
    document.querySelectorAll('[data-intent]').forEach(function (b) { b.disabled = false; b.classList.remove('chosen'); });
    $('intentThanks').hidden = true;

    var box = $('rightsList');
    box.textContent = '';
    d.rights.forEach(function (t) { var div = document.createElement('div'); div.className = 'r-item'; div.textContent = t; box.appendChild(div); });
    $('disclaimer').textContent = d.disclaimer;
    $('listInfo').textContent = 'Danh sách cấp phép: ' + d.licence.listVersion + '. ' + d.licence.listSource;
    $('thanks').hidden = true;
    document.querySelectorAll('[data-helpful]').forEach(function (b) { b.disabled = false; });
  }

  // ---- result actions
  $('backBtn').addEventListener('click', function () { showScreen('screen-form'); });
  $('againBtn').addEventListener('click', function () {
    ['principal', 'repay', 'days', 'lender', 'contractAmt', 'installments', 'installAmt', 'statedRate', 'income'].forEach(function (id) { $(id).value = ''; });
    document.querySelectorAll('.sig').forEach(function (c) { c.checked = false; });
    setPayType('ONE_TIME');
    $('feeList').textContent = '';
    clearErrors();
    resetOcr();
    $('formNote').hidden = true;
    $('statusDot').style.background = '';
    showScreen('screen-form');
  });

  // The AI writes the "I already took this loan" steps and the letter when the section is opened.
  // A fixed text is shown only if the AI cannot answer.
  var kitState = 'idle', fallbackLetter = '';
  var FALLBACK_STEPS = [
    'Giữ lại ảnh hợp đồng, màn hình giải ngân, sao kê tài khoản và mọi tin nhắn với bên cho vay.',
    'Hỏi bên cho vay bằng văn bản về từng khoản phí và cách tính lãi.',
    'Đừng gửi thêm giấy tờ, danh bạ hay quyền truy cập điện thoại cho ứng dụng không rõ nguồn gốc.',
    'Nếu bị đe dọa hoặc làm phiền, lưu lại bằng chứng và báo công an phường/xã.'
  ];
  function showKit(steps, letter, note) {
    var box = $('kitSteps');
    box.textContent = '';
    steps.forEach(function (t) { var div = document.createElement('div'); div.className = 'r-item'; div.textContent = t; box.appendChild(div); });
    $('letterText').value = letter;
    $('letterText').hidden = false; $('copyLetter').hidden = false; $('kitFoot').hidden = false;
    $('kitAiMsg').textContent = note;
  }
  function loadKit() {
    var d = lastD;
    if (!d || kitState !== 'idle') { return; }
    kitState = 'loading';
    $('kitAiMsg').textContent = 'AI đang soạn nội dung riêng cho khoản vay của bạn…';
    var body = {
      lenderName: d.lenderName, principalVnd: d.principalVnd, totalRepayVnd: d.totalRepayVnd,
      termDays: d.repaymentType === 'MONTHLY' ? null : d.termDays,
      installments: d.repaymentType === 'MONTHLY' ? d.installments : null,
      installmentVnd: d.repaymentType === 'MONTHLY' ? d.installmentVnd : null,
      statedRatePercent: typeof d.statedRatePercent === 'number' ? d.statedRatePercent : null,
      contractAmountVnd: d.contractGap ? d.contractGap.contractAmountVnd : null,
      hiddenCostVnd: d.hiddenCostVnd || null,
      fees: d.fees || [], riskCode: d.risk.code, licenceStatus: d.licence.status, signals: d.signals || []
    };
    fetch('/api/kit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, body: j }; }).catch(function () { return { ok: false, body: {} }; }); })
      .then(function (res) {
        if (res.ok && res.body.mode === 'ai') {
          showKit(res.body.steps, res.body.letter, 'Nội dung do AI soạn từ số liệu của bạn. Hãy đọc lại, điền họ tên và ngày, và sửa nếu cần trước khi gửi.');
          kitState = 'done';
        } else {
          showKit(FALLBACK_STEPS, fallbackLetter,
            (res.body && res.body.note) || 'AI chưa soạn được lúc này nên đang dùng nội dung mẫu. Đóng và mở lại mục này để thử lại.');
          kitState = 'idle';
        }
      })
      .catch(function () {
        showKit(FALLBACK_STEPS, fallbackLetter, 'Không kết nối được máy chủ. Đang dùng nội dung mẫu.');
        kitState = 'idle';
      });
  }
  $('kitBox').addEventListener('toggle', function () { if (this.open) { loadKit(); } });

  $('copyLetter').addEventListener('click', function () {
    var t = $('letterText');
    t.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    if (!ok && navigator.clipboard) { navigator.clipboard.writeText(t.value).then(function () { $('copyOk').hidden = false; }); return; }
    $('copyOk').hidden = !ok;
  });
  document.querySelectorAll('[data-intent]').forEach(function (b) {
    b.addEventListener('click', function () {
      fetch('/api/intent', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ choice: b.getAttribute('data-intent') })
      }).catch(function () { });
      document.querySelectorAll('[data-intent]').forEach(function (x) { x.disabled = true; });
      b.classList.add('chosen');
      $('intentThanks').hidden = false;
    });
  });

  document.querySelectorAll('[data-helpful]').forEach(function (b) {
    b.addEventListener('click', function () {
      fetch('/api/feedback', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ helpful: b.getAttribute('data-helpful') === 'true' })
      }).catch(function () { });
      document.querySelectorAll('[data-helpful]').forEach(function (x) { x.disabled = true; });
      $('thanks').hidden = false;
    });
  });

  showScreen('screen-consent');
})();
