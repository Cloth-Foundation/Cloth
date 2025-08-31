![Logo](https://github.com/LoomFoundation/.github/blob/main/Logos/PNG/File%20Logos/Security.png?raw=true)

# Security Policy

## Supported Versions

The Loom team currently supports the latest stable release of the Loom compiler and standard library. Older versions may receive security patches at the discretion of the Loom Steering Council.

| Version | Status     |
|---------|------------|
| latest  | ✅ Supported |
| dev/nightly | ⚠️ Best effort support |
| older releases | ❌ Unsupported unless LTS declared |

---

## Reporting a Vulnerability

If you discover a security vulnerability in the Loom language, compiler, or any official tool or repository, **please report it privately and responsibly.**

### Contact Method

Please email:

**security@loomlang.org**

Include the following:

- Description of the vulnerability
- Reproduction steps (if applicable)
- Affected components and versions
- Impact and severity (data corruption, RCE, etc.)
- Your name or handle (optional)

We aim to respond within **48 hours** and provide regular updates until the issue is resolved.

---

## Disclosure Process

1. **Initial Triage**: Confirm the report and evaluate its severity.
2. **Private Fix Development**: The Loom core team develops and tests a fix.
3. **Coordinated Disclosure**: If applicable, we work with downstream tools or users to patch affected systems.
4. **Public Release**:
    - Release notes will acknowledge the issue (and credit the reporter unless anonymity is requested).
    - A CVE may be requested for serious vulnerabilities.
    - A patched version will be published along with upgrade instructions.

---

## Security Scope

This policy applies to:

- Loom compiler and runtime
- Standard library modules
- Official language tools and formatters
- Any critical infrastructure in the [Loom Foundation GitHub organization](https://github.com/LoomFoundation)

**This policy does not cover:**
- Third-party tools or libraries
- User-written Loom programs with poor practices
- Outdated or forked versions not maintained by the Loom team

---

## Best Practices for Users

- Always use the latest stable Loom release.
- Avoid executing untrusted `.lm` or `.loom` code.
- Consider sandboxing Loom applications if working with external input.

---

## Recognition

The Loom Language Team greatly appreciates the efforts of ethical hackers and security researchers. Responsible disclosures help keep our community safe.

---

**Thank you for helping keep Loom secure.**  
— *The Loom Steering Council*
