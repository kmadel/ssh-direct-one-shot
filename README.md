Usage
=====

Installing this plugin automatically makes your Jenkins instance
resolve node labels of the form:

ssh-direct:<hostname>:<user>:<password>:<javaBinary>:<jenkinsWorkDir>:<launcherPrefix>:<launcherSuffix>

By automatically adding a new node to Jenkins using the
https://wiki.jenkins-ci.org/display/JENKINS/SSH+Slaves+plugin

Parameters for the new node are taken from the label string and
map directly to parameters documented in the above plugin.

Any errors starting up the SSH node will be printed to the job's
console and cause the Job to fail.
