# TrueRate

Free web tool: enter 4 loan numbers, get the true annual rate (APR), the lender's licence status, a risk label
and a plain-language explanation. Java 17 + Spring Boot 3, static web UI, one AI call per check.

## Run

1. `run.bat` (Windows), or `mvn spring-boot:run` with JDK 17.
2. Open http://localhost:8080

## Add the AI key later

Create a file named `.env` in this folder:

```
ANTHROPIC_API_KEY=sk-ant-...
```

Restart. The chip at the top of the page changes from "chế độ mẫu" (fixed explanation templates) to "AI giải thích: bật".
Without a key everything else still works; only the explanation text is a fixed template.

## Deploy

The `Dockerfile` works on any host that builds from a Git repo (Render, Railway). Set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`
in the host's environment variables, never in the repo. The port comes from `PORT` when the host sets it, otherwise 8080.
Check it with `GET /api/health`: `aiMode` must be `online`.

## How it works

| Step | Class | Type |
|---|---|---|
| Validate input | `core/InputValidator` | code |
| APR = (1 + (R - P) / P)^(365 / d) - 1 | `core/AprCalculator` | code |
| Licence check (exact match only) | `license/LicenseRegistry` | code |
| Risk label (licence x rate) | `risk/RiskClassifier` | code |
| Rights text | `risk/RightsGuide` | fixed text |
| Explanation + next step | `agent/ExplainerAgent` | AI (1 call) |
| Safety check of the AI reply | `agent/OutputValidator` | code |

The agent never calculates or labels. It receives facts decided by code and must answer through a forced tool schema
(`next_step` is an enum). The reply is checked (banned words, next step vs risk level); on any failure or without a key,
`agent/FallbackExplainer` returns a fixed text. The API key stays on the server.

## Read from a photo (OCR)

`POST /api/ocr` (multipart, `file` + `consent=true`) reads ONLY four fields: amount received, total to repay,
term in days, lender legal name. Names, ID numbers, phone numbers and addresses are never returned. The image stays
in memory for the request, is not stored or logged, and is re-encoded in the browser (EXIF and GPS removed).
The user must tick a confirmation that the four boxes match the original contract before the result is shown.
Uses the same provider as the explainer (Claude if its key is set, otherwise OpenAI vision).

## Before launch

- `licensed-lenders.json` is a DEMO list. Replace it with the official SBV licensed list.
- Rights text and the 20% / 100% reference levels must be reviewed by a lawyer.
- Tests: `mvn test` (21 tests: APR, labelling matrix, licence matching, output validation, agent against a fake API).
