# Code of conduct

## The short version

Be decent to people. Argue with the code, not with the person who wrote it.

## The standard

This project adopts the [Contributor Covenant v2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/).
Read it there rather than here — copying 4,000 words into this repository would only mean one more
copy to keep in sync.

## What that means in review

This project is about running scripts on other people's servers, so review here is unusually
sceptical by design. That is aimed at changes, never at contributors:

- **"This widens what can run without a human" is a technical objection.** Expect it, answer it with
  the guardrail or the test that closes it, and do not read it as distrust.
- **A rejected PR is not a rejected person.** Most rejections here are "the invariant this breaks
  matters more than the convenience it adds", which is a statement about the diff.
- **Do not smuggle.** Quietly widening the allowlist, loosening a guardrail regex, or removing a
  check inside an unrelated refactor is the one behaviour treated as bad faith rather than as a
  disagreement.

## Reporting

Contact the maintainer directly through GitHub — open a private security advisory (see
[SECURITY.md](SECURITY.md)) if the report itself is sensitive, or a direct message otherwise. There
is no committee; this is a small project and reports are handled by the maintainer.

Reports are read in confidence. Consequences range from a request to change behaviour up to a block
from the repository, and are the maintainer's call.
