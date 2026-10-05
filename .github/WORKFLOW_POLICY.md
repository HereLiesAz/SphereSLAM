# Workflow policy

Automation for HereLiesAz repositories is centralized in `HereLiesAz/workflows`.

## Rules

- New workflow implementations are submitted to the central workflows repository first.
- A new workflow must be generalized so another repository can use the same implementation through inputs/profile data.
- Repository names, package/application IDs, release names, branch names, paths, and service-specific settings must not be hard-coded when they can be inputs.
- Existing central/repository secrets must be reused whenever they satisfy the requirement.
- If a genuinely new secret is required, the submission must state:
  - the exact GitHub secret name;
  - what system/API it authenticates to;
  - what operation the workflow needs it for.
- A missing required secret must fail explicitly with its name and purpose.
- Generated target repositories should contain only controller-managed proxies, not copied central implementations.

See `.github/workflow-request.yml` for the current menu and request shape.
