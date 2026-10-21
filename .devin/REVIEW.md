# Review Guidelines

## Reviewing stacked PRs

Check whether the PR you're reviewing is part of a stack. If so, examine the
later PRs in the stack to see if they already address any findings or
questions you would otherwise have pointed out. Only comment about findings
that are already addressed later in the stack if they would cause data
corruption or similarly severe bugs, and in your comment, make note of the
fact that the problem is fixed in the later PR.

We will use that to help decide when it's safe to partially merge a stack.

## Wait for Codex

We also use Codex to review PRs, and it's annoying when multiple review tools
post the same findings. Wait up to 10 minutes for Codex to finish its review
before posting your own review findings, and don't post any findings that
Codex already flagged. If Codex hasn't finished its review within 10 minutes,
go ahead with your own review. Codex doesn't always post a "no issues found"
comment, so you'll need to poll its most recent review status comment to
wait for the status to change.

## Don't comment on merged PRs

Before posting review comments, check to see if the PR has been merged, and
if so, don't post any feedback.
