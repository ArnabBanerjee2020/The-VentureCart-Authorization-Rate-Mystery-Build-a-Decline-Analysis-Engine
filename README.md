# VentureCart Decline Analysis Engine

A Kotlin/Ktor backend that ingests VentureCart's transaction export, computes
payment authorization metrics, and exposes an API for slicing decline
patterns by PSP, country, card type, decline reason, and time.

## Stack & design choices

- **Ktor** (Netty engine) for the HTTP server — lightweight, no code
  generation, easy to read top to bottom.
- **kotlinx.serialization** for JSON.
- **In-memory store** (`TransactionRepository`) instead of a database.
  For an analysis engine like this, that's a deliberate tradeoff: it keeps
  setup to "clone and run" with zero external services, and Kotlin's
  collection operations (`groupBy`, `filter`, `sumOf`) comfortably handle
  10,000+ records with room to spare. If this needed to survive restarts or
  scale past a few hundred thousand rows, `TransactionRepository` is the one
  file you'd swap for a real database — nothing else in the codebase knows
  or cares how storage works.

## Project layout

```
src/main/kotlin/com/venturecart/decline/
  Application.kt          # server bootstrap, plugins, auto-load of test data
  model/Transaction.kt     # domain model + decline code reference table
  repository/TransactionRepository.kt   # in-memory store + CSV ingestion
  service/MetricsService.kt     # auth rate, breakdowns, soft/hard decline analysis
  service/PatternService.kt     # stretch goal: hour-of-day / day-of-week pattern detection
  service/HypothesisService.kt  # stretch goal: root-cause hypothesis generator
  routes/Routes.kt         # HTTP endpoints
  dto/Responses.kt         # JSON response shapes
  datagen/GenerateTestData.kt   # synthetic 60-day dataset generator
test-data/transactions.csv     # generated dataset (created by generateData task)
```

## Running it

Requires JDK 17+ and Gradle. This project doesn't ship a compiled
`gradlew` wrapper jar (it was built in a sandbox with no internet access to
download one) — either generate it yourself or use a local Gradle install.

```bash
# one-time, if you want ./gradlew: 
gradle wrapper --gradle-version 8.7

# 1. Generate the 60-day synthetic dataset (12,000 transactions)
gradle generateData
# -> writes test-data/transactions.csv and prints a stats summary to the console

# 2. Run the server (auto-loads test-data/transactions.csv on boot if present)
gradle run
# -> API listening on http://localhost:8080
```

If you'd rather load data manually (e.g. a different CSV), skip step 1's
auto-load and call the ingest endpoint instead:

```bash
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{"filePath": "test-data/transactions.csv"}'
```

CSV schema (header required):
```
transaction_id,timestamp,amount,currency,country,psp,card_type,bin,status,decline_reason_code,decline_type
```
`timestamp` is ISO-8601 UTC (e.g. `2026-05-01T14:32:07Z`). `decline_reason_code`
and `decline_type` are blank for approved rows. `status` is `approved` or
`declined`; `decline_type` is `soft` or `hard`.

## API Reference

All endpoints are under `/api`. `from` / `to` query params are optional on
every metrics endpoint and accept either a plain date (`2026-05-01`) or a
full ISO instant (`2026-05-01T00:00:00Z`); omitting both returns metrics
over the entire dataset.

### `GET /api/health`
Quick liveness + record count check.
```json
{ "status": "ok", "transactionsLoaded": 12000 }
```

### `POST /api/ingest`
Loads (and replaces) the in-memory dataset from a CSV path on disk.

Request:
```json
{ "filePath": "test-data/transactions.csv" }
```
Response:
```json
{
  "filePath": "test-data/transactions.csv",
  "recordsLoaded": 12000,
  "recordsRejected": 0,
  "totalRecordsInStore": 12000
}
```

### `GET /api/metrics/summary?from=2026-05-01&to=2026-05-07`
Overall authorization rate plus breakdowns by PSP, country, and card type.
```json
{
  "range": { "from": "2026-05-01T00:00:00Z", "to": "2026-05-07T23:59:59Z", "transactionCount": 1400 },
  "overall": { "key": "overall", "totalTransactions": 1400, "approved": 868, "declined": 532, "authRatePct": 62.0 },
  "byPsp": [
    { "key": "GlobalPay", "totalTransactions": 470, "approved": 175, "declined": 295, "authRatePct": 37.2 },
    { "key": "MercadoProcessing", "totalTransactions": 520, "approved": 385, "declined": 135, "authRatePct": 74.0 },
    { "key": "PayConnect", "totalTransactions": 410, "approved": 322, "declined": 88, "authRatePct": 78.5 }
  ],
  "byCountry": [ /* same shape, keyed by country */ ],
  "byCardType": [ /* same shape, keyed by Visa/Mastercard/Amex */ ]
}
```
(Numbers above are illustrative — run `gradle generateData` for the exact
figures from your seeded dataset.)

### `GET /api/metrics/declines?from=&to=`
Soft vs. hard decline breakdown and top decline reason codes.
```json
{
  "range": { "from": null, "to": null, "transactionCount": 12000 },
  "declineTypeBreakdown": {
    "totalDeclines": 4680,
    "softDeclines": 3650,
    "hardDeclines": 1030,
    "softDeclinePct": 78.0,
    "hardDeclinePct": 22.0,
    "potentiallyRecoverableRevenueShare": "891542.30 (78.1% of declined transaction value)"
  },
  "topDeclineReasons": [
    { "code": "51", "description": "Insufficient funds", "type": "SOFT", "count": 1450, "pctOfAllDeclines": 31.0 },
    { "code": "05", "description": "Do not honor", "type": "SOFT", "count": 935, "pctOfAllDeclines": 20.0 }
  ]
}
```

### `GET /api/metrics/patterns?from=&to=`  *(stretch goal)*
Hour-of-day and day-of-week auth rate buckets vs. baseline, flagged when the
deviation exceeds 8 percentage points on a bucket with 30+ transactions.
```json
{
  "baselineAuthRatePct": 61.4,
  "significanceThresholdPct": 8.0,
  "byHourOfDay": [
    { "bucket": "02:00-02:59", "totalTransactions": 309, "authRatePct": 47.9, "deltaVsBaselinePct": -13.5, "significant": true }
  ],
  "byDayOfWeek": [
    { "bucket": "Saturday", "totalTransactions": 1714, "authRatePct": 52.1, "deltaVsBaselinePct": -9.3, "significant": true }
  ],
  "notableFindings": [
    "Authorization rate drops 13.5 points during 02:00-02:59 (n=309) vs. the 61.4% overall baseline.",
    "Authorization rate drops 9.3 points on Saturday (n=1714) vs. the 61.4% overall baseline."
  ]
}
```

### `GET /api/metrics/hypotheses?from=&to=`  *(stretch goal)*
Data-driven root-cause candidates, computed fresh from whatever date range
you pass in (nothing here is hardcoded to a PSP/country/card name).
```json
{
  "hypotheses": [
    {
      "headline": "PSP 'GlobalPay' is dragging down the blended authorization rate",
      "evidence": "GlobalPay has a 37.2% auth rate across 3850 transactions vs. a 61.4% overall average — a 24.2 point gap.",
      "recommendation": "Investigate GlobalPay's technical integration ... consider shifting volume away via smart routing."
    },
    {
      "headline": "Mastercard transactions authorize significantly worse than Visa",
      "evidence": "Mastercard auth rate is 51.8% (n=3600) vs. 65.1% (n=7200) for Visa — a 13.3 point gap, consistent across PSPs.",
      "recommendation": "Check issuer-specific routing/BIN rules and 3-D Secure configuration for Mastercard ..."
    },
    {
      "headline": "GlobalPay's authorization rate collapses specifically on weekends",
      "evidence": "44.1% weekday vs. 26.3% weekend auth rate for GlobalPay — a 17.8 point weekend gap.",
      "recommendation": "Confirm whether GlobalPay has reduced weekend staffing/monitoring, a batch job, or issuer maintenance window on weekends."
    }
  ]
}
```

## Test data

`gradle generateData` produces a **seeded, reproducible** 12,000-row / 60-day
dataset (`test-data/transactions.csv`) with these patterns deliberately
baked in, so the API's output is always explainable against ground truth:

1. **GlobalPay authorizes far worse than PayConnect / MercadoProcessing**
   overall (base rates 45% vs. 79% / 77%, before further penalties).
2. **GlobalPay collapses further on weekends** (−15 points on top of its
   already-low base).
3. **All PSPs see an overnight dip** between 00:00–03:59 UTC (−10 points),
   driven by a spike in decline code `91` (issuer timeout).
4. **Mastercard authorizes ~12 points worse than Visa**, consistently
   across every PSP and country (Amex gets a small +2 point bonus).
5. **GlobalPay + Brazil + weekend skews heavily toward code `51`**
   (insufficient funds — soft/recoverable).

Countries: Brazil, Mexico, Argentina, Colombia, Spain, Germany. Card types:
Visa (60%), Mastercard (30%), Amex (10%). Decline codes and soft/hard
classification live in `model/Transaction.kt` (`DeclineCodes`), which both
the generator and the analysis engine read from, so ingestion and analysis
can never disagree about a code's type.

To regenerate with different parameters, edit the constants at the top of
`GenerateTestData.kt` (seed, days, records/day, base auth rates, penalties).

## Analysis summary

Run `gradle generateData` and the console prints the exact numbers for your
seeded dataset. Based on the deliberate patterns above, the expected story is:

VentureCart's authorization drop is not one single incident — it's the
combination of a materially underperforming PSP (**GlobalPay**, auth rate
roughly 20–30 points below the other two providers) whose problem gets
noticeably worse on weekends, layered on top of two systemic issues that cut
across every provider: an overnight authorization dip consistent with
issuer-timeout errors, and a persistent Mastercard-vs-Visa gap suggesting an
issuer- or scheme-level routing problem rather than a fraud issue. The good
news is that the majority of declines are classified as **soft** (driven
heavily by insufficient-funds and do-not-honor codes), meaning a meaningful
share of the lost $1.2M/month is plausibly recoverable through retry logic,
smart routing away from GlobalPay, and alternate payment options — rather
than requiring a fraud or compliance fix.

## Notes on scope

- No auth/rate limiting, per the brief's "don't worry about production
  concerns" — this focuses on correct calculations and clean API design.
- `TransactionRepository` uses `CopyOnWriteArrayList` for thread-safety
  under concurrent reads during ingestion; fine at this scale, would be the
  first thing to reconsider if this became a write-heavy service.
- Malformed CSV rows are skipped (logged to stderr with a line number)
  rather than failing the whole ingest, since a 10k+ row real-world export
  is likely to have a handful of bad rows.
