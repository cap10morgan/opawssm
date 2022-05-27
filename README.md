# opawssm

Generates temporary AWS session credentials from 1Password login items.

## Requirements

- Install 1Password itself if you haven't already
- Install the 1Password CLI version 2.0+ according to
  [these instructions](https://developer.1password.com/docs/cli/get-started#install)

## Usage

If you haven't already, create a Login item in 1Password with at least the
following elements:

- A field labeled `AccessKeyId` with your IAM account's AccessKeyId in it.
  This field can be plain text.
- A field labeled `SecretAccessKey` with your IAM account's SecretAccessKey in 
  it. This should be a "password" field so that its contents are normally
  concealed.
  - It might be nice to put these fields into a section labeled `IAM` but
    opawssm doesn't care one way or the other so feel free to put them wherever
    makes sense for you in the Login item.
- A TOTP (one-time password) field (label doesn't matter) configured as the MFA
  device for your IAM account.

Optional elements:

- A username matching your AWS web console username
- Your AWS IAM account-id in a field labeled `AccountId`
  - If you have both of the above optional elements opawssm can skip an AWS
    API request to obtain your MFA device serial / ARN. This should speed
    things up.
- OR instead of the above you can put your whole MFA ARN into a field labeled
  `MfaArn` to achieve the same performance optimization.
   - By default opawssm will update your 1Password item with this value upon
     first discovery to speed up subsequent usage. One consequence of this is
     that you'll need to update this field in 1Password if you change MFA
     devices. If you don't want this, use the `--no-update` option.
- An `AWS` tag or a tag that starts with `AWS/` (e.g. `AWS/foo`)
  - You can use a different tag with the `--tag` / `-t` option.
  - This can be handy if you have an account you'd like to default to when one
    is not specified in the command.

With the above setup in 1Password, you can run shell commands or login to the
AWS web console using temporary credentials.

Here's how:

- running shell commands: `opawssm exec 'Login item name' -- your command`
- logging into AWS web console: `opawssm login 'Login item name'`
  - ...where 'Login item name' is the name of your 1Password Login item you
    created. If you omit this opawssm will use the first item found w/ the
    specified or default `AWS` / `AWS/*` tag.
