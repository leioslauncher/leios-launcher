# Security

## Reporting a problem

Please don't describe security problems in a public issue.

Use GitHub's private reporting: open the **Security** tab of this repository and choose **Report a vulnerability**.

Please include what you found, the steps to reproduce it, the app version and your device. I'll reply as soon as I can, but this is a one-person project, so I can't promise a response time.

## What counts

- A way around the PIN lock or guest mode, other than the ADB reset below.
- Personal data leaving the device in a way [PRIVACY.md](PRIVACY.md) doesn't describe.
- A way for another app to misuse the launcher's accessibility, notification or device-admin access.

## Known and intended

- The PIN lock is a household control, not strong security. Anyone with ADB access can reset a forgotten PIN.
- While network debugging is on, a device you accept can control the TV. Turn it off when you're done.

## Supported versions

Only the latest version gets fixes.
