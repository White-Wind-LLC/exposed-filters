## REST Filters Module

### Purpose

- **Goal**: Convert an incoming HTTP request body (JSON) into a normalized, type-safe `FilterRequest` describing
  filtering as a tree of field conditions and logical groups.
- **Consumers**: Downstream modules (e.g., `:jdbc`) can translate the `FilterRequest` into database queries.

### Entry point

- `ApplicationCall.receiveFilterRequestOrNull(): FilterRequest?`
    - Returns `null` if the request body is empty or contains no valid conditions.
    - Throws `BadRequestException` when both `filters` and `children` are present at the same level in the root request
      body.

### JSON input formats

Two shapes are supported. Only one of the top-level keys `filters` or `children` may be present.

- **Flat**
    - Keys: `filters` (required), `combinator` (optional, defaults to `AND`).
    - `filters` is a map: `fieldName -> List<Condition>`.

- **Tree (nested)**
    - Keys: `children` (required), `combinator` (optional, defaults to `AND`).
    - Each child node may contain `filters`, `children`, or both, plus an optional `combinator`.

### Condition shape and operators

A condition references one field and one operator.

- `IN`, `NOT_IN`, `BETWEEN`:
    - Use `values: List<String>`
- `IS_NULL`, `IS_NOT_NULL`:
    - No values required
- Other operators (`EQ`, `NEQ`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `GT`, `GTE`, `LT`, `LTE`):
    - Use `value: String`

Invalid conditions (missing required value(s)) are ignored. Nodes that become empty are removed during normalization.

### Normalization rules

- Empty leaves and groups are removed.
- Groups with a single child collapse to that child.
- Flat bodies:
    - `combinator = AND` → a `FilterLeaf` is returned.
    - `combinator = OR` → the leaf is wrapped as `FilterGroup(OR, [leaf])`.
- Tree bodies:
    - Node `combinator` defaults to `AND` when omitted.

The final result is a minimal `FilterNode` tree or `null` if no valid conditions exist.

### Domain model (from `:filters:core`)

- `FilterOperator`:
  `EQ, NEQ, CONTAINS, STARTS_WITH, ENDS_WITH, IN, NOT_IN, BETWEEN, GT, GTE, LT, LTE, IS_NULL, IS_NOT_NULL`.
- `FilterCombinator`: `AND | OR`.
- `FieldFilter`: `{ field: String, operator: FilterOperator, values: List<String> }`.
- `FilterLeaf`: `{ predicates: List<FieldFilter> }`.
- `FilterGroup`: `{ combinator: FilterCombinator, children: List<FilterNode> }`.
- `FilterRequest`: `{ root: FilterNode? }` with `isEmpty` helper.

### Parsing characteristics

- Uses Kotlinx Serialization with lenient settings: unknown keys are ignored, input values are coerced when possible.
- Body is read as a raw string and decoded. Decoding failures yield `null` (except the mutual exclusivity rule, which
  throws).

### Examples

- Flat request

```json
{
  "combinator": "AND",
  "filters": {
    "status": [ { "op": "IN", "values": ["OPEN", "CLOSED"] } ],
    "createdAt": [ { "op": "BETWEEN", "values": ["2025-01-01", "2025-01-31"] } ],
    "customer": [ { "op": "CONTAINS", "value": "Acme" } ]
  }
}
```

- Nested request

```json
{
  "combinator": "AND",
  "children": [
    {
      "filters": {
        "status": [ { "op": "EQ", "value": "OPEN" } ]
      }
    },
    {
      "combinator": "OR",
      "children": [
        { "filters": { "priority": [ { "op": "GTE", "value": "2" } ] } },
        { "filters": { "assignee": [ { "op": "IS_NULL" } ] } }
      ]
    }
  ]
}
```

### Usage in Ktor

```kotlin
val filter: FilterRequest? = call.receiveFilterRequestOrNull()
if (filter != null && !filter.isEmpty) {
    // Pass filter.root (FilterLeaf/FilterGroup) to the query-building layer
}
```

### Error handling and guarantees

- A valid outcome is either `null` (no filters) or a normalized, non-empty filter tree.
- Operator/value shapes are validated per operator.
- The logical structure is compacted for efficient processing downstream.
