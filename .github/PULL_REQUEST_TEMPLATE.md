## Summary

<!-- What does this change do, and why? Link the issue it closes. -->

Closes #

## Test plan

<!-- How was this verified? List unit/integration tests added or updated. -->

- [ ] `./mvnw clean verify` passes locally
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated (or: no I/O boundary touched, N/A)

## Checklist

- [ ] All commits are signed off (`git commit -s`) — required by DCO
- [ ] No new dependency on `core` or `application` from Spring, JPA, or any framework
- [ ] Module dependency rules respected (see [CONTRIBUTING.md](../CONTRIBUTING.md#module-dependency-rules--cannot-be-violated))
- [ ] Docs updated if behavior changed (KDoc, OpenAPI, README, Bruno collection)
