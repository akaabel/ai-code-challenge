# XTDB Datalog Query Guide

## Basics

Every document in XTDB is a set of attribute/value facts. A query uses `:find` (what to return) and `:where` (the patterns to match).

A clause like `[e :candidate/ticker ticker]` means: *find any entity `e` that has a `:candidate/ticker` attribute, and bind its value to the symbol `ticker`*. Symbols (lowercase words) are logic variables — they get unified across clauses.

## Query Playground Examples

### 1. Candidate tickers (simple)
```clojure
{:find [ticker]
 :where [[e :candidate/ticker ticker]]}
```
Find all entities that have a `:candidate/ticker` attribute, return the value. `e` is discarded — you only want the ticker string.

### 2. All candidates
```clojure
{:find [(pull e [:xt/id :candidate/ticker ...])]
 :where [[e :candidate/ticker]]}
```
Same match — any entity with `:candidate/ticker` — but `pull` fetches a whole sub-document (a map of the listed keys) for each matching entity instead of picking individual attributes. `:xt/id` is the document's primary key.

### 3. Analyses — rating ≥ 7
```clojure
{:find [(pull e [...])]
 :where [[e :analysis/rating r]
         [(>= r 7)]]}
```
Two clauses chained together. First, match entities with an `:analysis/rating` and bind it to `r`. Second, `[(>= r 7)]` is a *predicate clause* — a plain Clojure expression that filters out rows where the condition is false. Both clauses must hold for a row to be returned.

### 4. Approved proposals
```clojure
{:find [(pull p [...])]
 :where [[p :trade-proposal/decision :approved]]}
```
When you put a literal value (not a symbol) in the third position of a clause, it acts as an equality filter. Matches entities where `:trade-proposal/decision` is exactly `:approved`.

### 5. Resized proposals
Same pattern as above, filtering for `:trade-proposal/decision :resized`.

### 6 & 7. All orders / All fills
```clojure
{:where [[o :order/ticker]]}
```
`[o :order/ticker]` means: *match any entity that has an `:order/ticker` attribute at all* — the value isn't bound to a symbol, so it's an existence check. This is how you select all documents of a given "type".

### 8. All LLM calls
Same pattern — existence check on `:llm-call/model` selects all LLM call records.

### 9. News — negative sentiment
```clojure
{:where [[n :news-report/sentiment s]
         [(< s 0)]]}
```
Bind the sentiment score to `s`, then filter with a predicate `(< s 0)`. Same pattern as the rating query.

### 10. Pipeline join: ticker + action + decision
```clojure
{:find [ticker action decision]
 :where [[c :candidate/ticker ticker]
         [a :analysis/candidate-id (:xt/id c)]
         [a :analysis/action action]
         [p :trade-proposal/analysis-id (:xt/id a)]
         [p :trade-proposal/decision decision]]}
```
A **join** across three document types. Each clause introduces or re-uses symbols:
- `c` is bound to a candidate entity, `ticker` to its ticker.
- `[a :analysis/candidate-id (:xt/id c)]` finds an analysis whose `candidate-id` foreign key matches `c`'s id — this is the join condition.
- `[a :analysis/action action]` pulls the action from that same analysis.
- `[p :trade-proposal/analysis-id (:xt/id a)]` joins again from analysis to proposal.
- `decision` is pulled from the proposal.

Result: rows of `[ticker action decision]` tracing a full pipeline path from scan → analysis → proposal.

### 11. Settings document
```clojure
{:where [[s :settings/max-trades-per-day]]}
```
Existence check for the settings document. Since there is only one, this returns a single row.

## Mental Model

Every clause is a constraint that logic variables must satisfy simultaneously. Shared variables across clauses act as joins. There is no schema — any entity can have any attribute, so "type" is implied by which attributes an entity has.

---

## Datalog — deeper

Datalog is a declarative query language descended from Prolog. Unlike SQL where you describe *how to join tables*, in Datalog you state *what must be true* and the engine figures out the plan. There are no tables — just a flat sea of facts in the form `[entity attribute value]` (called EAV triples).

**Logic variables unify, they don't assign.** When the same symbol appears in multiple clauses, the engine finds all combinations of values that satisfy all clauses simultaneously:

```clojure
{:find [ticker rating]
 :where [[e :candidate/ticker ticker]
         [a :analysis/ticker ticker]   ; same ticker — effectively a join
         [a :analysis/rating rating]]}
```

There is no `JOIN ON` keyword — shared variables *are* the join.

**Negation** — assert that something does NOT exist:

```clojure
{:find [ticker]
 :where [[c :candidate/ticker ticker]
         (not [o :order/ticker ticker])]}  ; scanned but never ordered
```

**Aggregates** work inside `:find`:

```clojure
{:find [ticker (count e)]
 :where [[e :order/ticker ticker]]}
```

---

## The `pull` operation

`pull` is a graph-pull API — it fetches a document (or a graph of linked documents) by specifying a *pattern* of which attributes to include. It lives inside `:find`:

```clojure
{:find [(pull e pattern)]
 :where [...]}
```

### Pattern syntax

| Pattern | Meaning |
|---|---|
| `[:xt/id :foo/bar]` | Return only these attributes |
| `[*]` | Return all attributes (wildcard) |
| `[:foo/bar {:foo/child [:child/name]}]` | Nested pull — follow a reference and pull the child document |
| `[:foo/bar {:foo/tags [*]}]` | Pull all attributes from each item in a collection |

### Why not just bind variables in `:find`?

Binding individual attributes works fine for a few fields:

```clojure
{:find [ticker rating action]
 :where [[e :analysis/ticker ticker]
         [e :analysis/rating rating]
         [e :analysis/action action]]}
```

But `pull` is better when you want many fields, or when you want the result as a **map** rather than a tuple — it's much easier to work with downstream:

```clojure
; pull returns a map:   {:analysis/ticker "NVDA" :analysis/rating 8 ...}
; binding returns a tuple: ["NVDA" 8 "buy"]
```

### The wildcard `[*]`

```clojure
{:find [(pull e [*])]
 :where [[e :candidate/ticker]]}
```

Returns every attribute on every candidate document. Convenient for exploration, but avoid it in production queries — it fetches data you may not need and the shape of results is unpredictable when documents vary.

### Following references (graph traversal)

XTDB documents can reference other documents by `:xt/id`. `pull` can follow those references in one query:

```clojure
{:find [(pull p [:trade-proposal/ticker
                 :trade-proposal/decision
                 {:trade-proposal/analysis [:analysis/rating
                                            :analysis/action]}])]
 :where [[p :trade-proposal/decision]]}
```

This returns proposal maps that *include* the nested analysis map — no second query needed. This is the "graph" in graph-pull: you can traverse edges (foreign key references) declaratively.
