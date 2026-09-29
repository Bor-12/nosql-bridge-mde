# Bridge DSL — Design Decisions

This document records the main design decisions adopted for the Bridge DSL.

## 1. File extension

**DECISION**

Bridge definitions use the `.brg` extension.

**MOTIVATION**

`.dsl` was considered too generic. `.brg` identifies files as bridge definitions while remaining short and independent from a specific database technology.

---

## 2. Basic bridge structure

**DECISION**

A bridge contains:

- one source;
- one target;
- one mapping block.

The initial implementation uses MongoDB as source and DynamoDB as target.

Example:

```brg
bridge ProductBridge {

    source MongoDB {
        collection "products"
    }

    target DynamoDB {
        table "Products"
        partitionKey "id"
    }

    mapping {
        _id -> id
        name -> name
    }
}
```

**MOTIVATION**

The initial structure is deliberately small.
It provides the minimum concepts required to describe a complete MongoDB to DynamoDB bridge without introducing execution or infrastructure concerns into the language.

---

## 3. Mapping notation

**DECISION**

Mappings use the notation:

```brg
sourceField -> targetField
```

The same construct is used for direct mappings and field renaming.

Examples:

```brg
name -> name
_id -> id
```

**MOTIVATION**

Using a single mapping construct keeps the language simple and avoids introducing separate syntax for copies and renames.

---

## 4. Nested source fields

**DECISION**

Nested source fields use dot notation.

Example:

```brg
category.name -> category
```

**MOTIVATION**

Dot notation provides a concise way to navigate nested MongoDB documents without introducing additional language constructs.

---

## 5. Transformations

**DECISION**

Transformations are specified with the `using` keyword.

Example:

```brg
price -> price using toNumber
```

The first supported transformation is:

```text
toNumber
```

For the current design, `toNumber` converts a valid numeric string into a number.

Example:

```text
"249.99" -> 249.99
```

If conversion is impossible, the transformation fails.

For the current behavior:

- the current record is considered invalid;
- no DynamoDB item is generated for that record;
- the failure is identified as a transformation error.

**MOTIVATION**

Transformations are kept separate from connectors so that data conversion logic does not depend on MongoDB or DynamoDB APIs.

---

## 6. Default values

**DECISION**

Default values use the `default` keyword.

Example:

```brg
status -> status default "ACTIVE"
```

`default` is applied when the source field:

- does not exist; or
- exists with the value `null`.

**MOTIVATION**

Missing and `null` values are treated uniformly in the first version to keep the transformation semantics simple.

Configurable null policies are not currently included.

---

## 7. BridgeExecutionPlan

**DECISION**

The Java runtime will not consume the textual `.brg` definition directly.

The DSL definition is transformed into a normalized `BridgeExecutionPlan`.

Conceptually:

```text
.brg definition
      ↓
Langium
      ↓
BridgeExecutionPlan
      ↓
runtime
```

The `BridgeExecutionPlan` contains the normalized source, target and mapping information required by the runtime.

Example:

```json
{
  "bridgeName": "ProductBridge",
  "source": {
    "technology": "mongodb",
    "collection": "products"
  },
  "target": {
    "technology": "dynamodb",
    "table": "Products",
    "partitionKey": "id"
  },
  "mappings": [
    {
      "source": "_id",
      "target": "id"
    }
  ]
}
```

**MOTIVATION**

This separates the textual language from the runtime contract. The DSL syntax can evolve without forcing changes in the runtime as long as the `BridgeExecutionPlan` contract remains stable.

The `BridgeExecutionPlan` is an execution contract, not an intermediate data record.

---

## 8. Validation boundary

**DECISION**

Errors that depend only on the bridge definition should be detected before execution whenever possible.

Initial validation requirements include:

- a bridge must have a name;
- a source must be defined;
- a target must be defined;
- at least one mapping must exist;
- a target field must not be mapped more than once;
- the DynamoDB partition key must be producible by the mappings;
- transformation names must be supported.

Example of a definition error:

```brg
price -> price using unknownTransformation
```

This can be rejected before execution.

Errors that depend on actual source values are runtime errors.

Example:

```text
toNumber("not-a-number")
```

**MOTIVATION**

This distinction keeps language validation separate from errors that can only be detected while processing real data.

---

