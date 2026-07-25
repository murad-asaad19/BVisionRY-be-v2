# Agent decisions log

Append-only. Every judgement call made during an unattended run lands here so
the operator can review — and reverse — what they didn't get asked about.

`agent-policy.yml → run_mode.on_ambiguity: DECIDE_AND_LOG` requires an entry
whenever a ticket hits a question the policy doesn't answer. Anything in
`never_auto_decide` parks instead of being logged here.

**Format** — newest last, one block per decision:

```
## <ticket-id> · <short title>
**Ambiguity** — what the policy didn't answer.
**Options** — what was considered.
**Chose** — the decision, stated plainly.
**Why** — the reasoning, in a sentence or two.
**Reversing it** — what would have to change, and how expensive that is.
**Commit** — <sha>
```

Keep `Reversing it` honest. A decision that is cheap to undo needs no debate;
one that is expensive should say so loudly, because the operator did not get to
weigh in before it was made.

---

<!-- entries below -->
