# Privacy Filter – Design and Rules

This privacy filter is implemented as a small pipeline of independent components.
Each component has exactly one responsibility, making the system easy to understand,
extend, and maintain.

---

## Architecture Overview

```
Event
 └─ FilterService
      ├─ KeyPolicy   → structural decisions (drop / preserve / force)
      ├─ UrlPolicy   → context decisions (what is a URL, path vs query behavior)
      ├─ Redactor    → regex-based value redaction
      └─ Traverser   → recursive traversal + orchestration
```

| Component       | Responsibility |
|-----------------|----------------|
| `FilterService` | Composition root. Wires everything together and applies policies to an event. |
| `KeyPolicy`     | Key-based business rules: drop fields, preserve trusted values, force anonymization. |
| `UrlPolicy`     | Defines what is a URL field and how URLs are sanitized (path vs query). |
| `Redactor`      | Applies regex rules, UUID masking, and metrics. No structural logic. |
| `Traverser`     | Walks nested data structures and delegates decisions to the policies. |

Responsibilities are isolated:
- Structural rules do not know about regexes.
- Regex rules do not know about traversal.
- URL behavior is fully contained in one place.

---

## Data Flow

1. `FilterService` receives an `Event`
2. Payload header fields are copied as-is
3. `UrlPolicy` sanitizes:
   - `payload.url`
   - `payload.referrer`
4. `Traverser` walks `payload.data` recursively:
   - Applies `KeyPolicy` for structural decisions
   - Uses `UrlPolicy` to detect URL contexts
   - Uses `Redactor` to sanitize string values
5. The sanitized event is returned

All behavior is deterministic and enforced by the test suite.

---

## Filter Model

The filter is built around **three independent layers**:

1. Structural transforms (key-based behavior)
2. URL policy (context-based handling)
3. Redaction rules (value-based detection)

Each layer solves a different problem and does not depend on the others.

---

## 1. Structural Transforms (Key-based rules)

These rules are applied based on the **field name**, before any regex-based redaction.

| Key / Condition | Behavior | Output |
|---------------|--------|------|
| `ip_address` | Remove field completely | *(key is dropped)* |
| `ip` | Replace with remote marker | `"$remote"` |
| `api_key` | Always preserved | unchanged |
| `device_id` | Always preserved | unchanged |
| `website` | Always preserved | unchanged |
| Advertising IDs:<br>`idfa`, `idfv`, `gaid`, `adid`, `android_id`, `aaid`, `msai`, `advertising_id` | Force anonymization | `"[PROXY]"` |
| Any map / list / set / array | Traversed recursively | structure preserved |

**Purpose:**  
Structural rules express business decisions about what is trusted, dropped, or forced
to anonymized values. No regex is involved here.

---

## 2. URL Policy (Context-based handling)

Only some fields are treated as *real URLs*.

| Location | URL handling |
|--------|-------------|
| `payload.url` | Treated as URL |
| `payload.referrer` | Treated as URL |
| `data.payload.url` | Treated as URL |
| `data.payload.referrer` | Treated as URL |
| Any other `url` field | Treated as a normal string |

For real URLs:

1. The value is split into:
   - **Path-part** (before `?`)
   - **Query string** (after `?`)
2. Redaction is applied to both parts, but with different rule sets.

### URL rule exclusions

| URL section | Disabled rules | Why |
|------------|--------------|----|
| Path-part | `PROXY-FILEPATH`, `PROXY-FNR`, `PROXY-ACCOUNT` | Prevent false positives such as file paths or numeric IDs in URLs |
| Query string | *(none)* | Query strings often contain real PII and must be fully sanitized |

**Purpose:**  
URL paths are treated as identifiers and routing data.  
Query strings are treated as user input and are fully sanitized.

---

## 3. Redaction Rules (Value-based, Regex)

These rules apply to all strings unless explicitly excluded by the URL policy.

| Rule | Label | Detects | Output |
|-----|------|-------|------|
| File path | `PROXY-FILEPATH` | Filesystem paths | `[PROXY-FILEPATH]` |
| National ID (FNR) | `PROXY-FNR` | 11-digit Norwegian IDs | `[PROXY-FNR]` |
| NAV ident | `PROXY-NAVIDENT` | `X123456` format | `[PROXY-NAVIDENT]` |
| Email | `PROXY-EMAIL` | Email addresses | `[PROXY-EMAIL]` |
| IP address | `PROXY-IP` | IPv4 strings | `[PROXY-IP]` |
| Phone | `PROXY-PHONE` | 8-digit numbers | `[PROXY-PHONE]` |
| Name | `PROXY-NAME` | Simple full-name patterns | `[PROXY-NAME]` |
| Address | `PROXY-ADDRESS` | Postal address-like patterns | `[PROXY-ADDRESS]` |
| Secret address | `PROXY-SECRET-ADDRESS` | “hemmelig adresse” | `[PROXY-SECRET-ADDRESS]` |
| Account number | `PROXY-ACCOUNT` | `1234.56.78901` format | `[PROXY-ACCOUNT]` |
| Organization number | `PROXY-ORG-NUMBER` | 9 digits | `[PROXY-ORG-NUMBER]` |
| License plate | `PROXY-LICENSE-PLATE` | `AB12345` format | `[PROXY-LICENSE-PLATE]` |
| Search query | `PROXY-SEARCH` | `?q=`, `?search=` etc | `[PROXY-SEARCH]` |

---

## 4. Preserved Patterns

Some values are temporarily masked to avoid accidental redaction.

| Pattern | Reason |
|-------|-------|
| UUIDs | Prevent partial regex matches |
| `nav123456`, `test654321` | Explicitly allowed identifiers |

They are restored unchanged after redaction.

---

## How to Extend

| Change you want | Where to implement |
|----------------|------------------|
| Preserve a new key | `KeyPolicy` |
| Drop a new key | `KeyPolicy` |
| Force-anonymize a key | `KeyPolicy` |
| Change URL behavior | `UrlPolicy` |
| Add a new PII regex | `FilterService.buildRules()` |
| Change traversal behavior | `Traverser` |

Most changes should touch exactly one file.
It acts as a deterministic and auditable privacy firewall.
---
