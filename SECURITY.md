![Logo](https://github.com/Cloth-Foundation/.github/blob/main/Logos/PNG/File%20Logos/Security.png?raw=true)

# Security Policy

## Supported Versions

The Cloth team currently supports the latest stable release of the Cloth compiler and standard library. Older versions may receive security patches at the discretion of the Cloth Steering Council.

| Version | Status     |
|---------|------------|
| latest  | ✅ Supported |
| dev/nightly | ⚠️ Best effort support |
| older releases | ❌ Unsupported unless LTS declared |

---

## Reporting a Vulnerability

If you discover a security vulnerability in the Cloth language, compiler, or any official tool or repository, **please report it privately and responsibly.**

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
2. **Private Fix Development**: The Cloth core team develops and tests a fix.
3. **Coordinated Disclosure**: If applicable, we work with downstream tools or users to patch affected systems.
4. **Public Release**:
    - Release notes will acknowledge the issue (and credit the reporter unless anonymity is requested).
    - A CVE may be requested for serious vulnerabilities.
    - A patched version will be published along with upgrade instructions.

---

## Security Scope

This policy applies to:

- Cloth compiler and runtime
- Standard library modules
- Official language tools and formatters
- Any critical infrastructure in the [Cloth Foundation GitHub organization](https://github.com/Cloth-Foundation)

**This policy does not cover:**
- Third-party tools or libraries
- User-written Cloth programs with poor practices
- Outdated or forked versions not maintained by the Cloth team

---

## Best Practices for Users

- Always use the latest stable Cloth release.
- Avoid executing untrusted `.co`, `.cloth`, or `.clib` code.
- Consider sandboxing Cloth applications if working with external input.

---

## Recognition

The Cloth Foundation greatly appreciates the efforts of ethical hackers and security researchers. Responsible disclosures help keep our community safe.

---

**Thank you for helping keep Cloth secure.**  
— *The Cloth Steering Council*
