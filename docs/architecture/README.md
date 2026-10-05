# Architecture decisions

This collects the design contracts in XaeroNav's implementation that are easy to lose sight of from local code comments alone.
The documents here are not a work history. They record the boundaries to uphold when changing the current implementation, and
the tests that verify those boundaries. For the rationale behind specific numbers, the comments next to the constants are canonical.

## Decisions

- [ADR-001: Three-stage route pipeline](001-route-pipeline.md)
- [ADR-002: Asynchronous state ownership](002-async-state.md)
- [ADR-003: Loader, Xaero hook, and distribution contracts](003-platform-integration.md)

## Updating these records

Commits that change the design update the code, the corresponding tests, and these documents in the same change. Don't append
past investigation logs or rejected proposals wholesale; keep only the decisions still in effect and the conditions for revisiting them.
