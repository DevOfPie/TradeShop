# Proposed workflows

Changes to `.github/workflows/` are written here and applied by the owner. This
file says why, what belongs here rather than in `ci/`, and how a proposal is
applied.

The process is [LinkCtrl's](https://github.com/DevOfPie/LinkCtrl), adopted here
on 2026-08-07 because this repository hit the identical wall. Nothing about it
is invented for TradeShop.

## Why a proposal and not a commit

The token building this repository is a fine-grained PAT without the `Workflows`
permission. GitHub refuses the **push**, not the merge:

```
! [remote rejected] ci-minimal-gate -> ci-minimal-gate
  (refusing to allow a Personal Access Token to create or update workflow
   .github/workflows/maven.yml without `workflow` scope)
```

Measured here 2026-08-07. Push access is otherwise real — a probe branch
carrying no workflow file pushed and was deleted. So there is no version of
"open a pull request and let the owner review it" that works: a branch carrying
a workflow change cannot leave the machine at all. That is why the change
arrives as a file at a path that is not `.github/workflows/`.

### Why the permission stays absent

It is not an oversight to be corrected. A workflow file is code that runs with
`GITHUB_TOKEN`, and a workflow's own `permissions:` block overrides the
repository default — that setting is a default, not a ceiling. Write access to
`.github/workflows/` therefore converts into `packages: write`,
`actions: write` (deleting run logs, which is the audit trail) and
`pages: write`, none of which are on the token. A `schedule:` or
`workflow_dispatch:` workflow also keeps running after the token is revoked.

The permission is a lever on all of that, not a lever on YAML. One manual step
per workflow change is the price, and workflow changes are rare by design — see
the split below.

## What lives where

| Change | Where | Needs the owner |
| --- | --- | --- |
| A new check, or a changed one | `ci/build.sh` | No |
| What a check actually does | `ci/*.sh` | No |
| Surefire config, plugin versions, dependency pins | `pom.xml` | No |
| Triggers, `permissions:`, `concurrency:` | `.github/workflows/` | **Yes** |
| `JAVA_VERSION`, `runs-on` | `.github/workflows/` | **Yes** |
| Action versions and their pins | `.github/workflows/` | **Yes** |

The left column is the common case and the right column is not, which is what
makes the manual step affordable. Adding a check is a script edit that reaches
the next push; changing what CI *is* takes a proposal.

**This matters for W5.** The test suite's gate — failing the build on skipped
tests, because MockBukkit reports unimplemented API as a skip and a build that
skips everything still exits 0 — is surefire configuration and a script edit.
Neither needs a proposal.

`sh ci/workflow-proposals.sh` prints which proposals are pending, with a diff
against the live file. It is deliberately **not** a gate and always exits 0: a
pending proposal is a normal state.

## Applying one

From a checkout with the owner's credentials:

```sh
cp ci/proposed/maven.yml .github/workflows/maven.yml
git rm .github/workflows/maven.yml.old
sh ci/workflow-proposals.sh          # must now report: applied
git add .github/workflows/maven.yml
git commit
```

Use `cp` rather than copying the text through an editor: only trailing newlines
are normalised in that comparison, and everything else counts, whitespace
included.

Then close the loop: move the row in TradeShop-Support's
`docs/records/workflow-changes.md` to *Made* with the commit, and **delete the
file from this directory**. A proposal that stays here after being applied
becomes a second copy of the workflow, free to drift from the one that runs —
which is the failure this directory would otherwise invite.

The tracker row lives in the support repository rather than here, because it is
a record and records never cross into this tree. The proposal file lives here
because it is code. That split is the same one that governs everything else in
this project.

## Writing one

- **No header addressed to the reviewer.** A proposal is a whole-file copy and
  applying it is `cp`, so anything the file says about itself lands on the live
  workflow. Do not write "apply this file" into a file that will *become* the
  workflow. The diff *is* the description, and `workflow-proposals.sh` prints
  it.
- **Say what is not changing.** The reviewer's question is always "does this
  alter what runs on push", and the answer belongs in the file's header comment.
  That is a comment about the workflow, not about the proposal, so it survives
  being applied without embarrassing anyone.
- **Change one thing.** A proposal is reviewed by a human reading YAML, and a
  diff that mixes a trigger change with a refactor gets approved for the
  refactor.
- Raise the row in `workflow-changes.md` at the same time, so a waiting change
  is as visible as a waiting defect.
