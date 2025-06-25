# Slack User Synchronization

The `SlackUserSyncJob` links intranet users with their Slack accounts.
It runs every night at 02:30 and performs the following steps:

1. Look up all users where `slackusername` is empty.
2. For each user, call Slack's `users.lookupByEmail` API using the admin bot token.
3. If a Slack user is found the `slackusername` column is updated with the returned id.
4. Users without a corresponding Slack account are skipped.

Detailed logging is added to track how many users are processed and any lookup errors.
