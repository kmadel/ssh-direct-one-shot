package com.delphix.jenkins.plugins.sshdirect;

import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.SubTask;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.*;
import hudson.util.Secret;
import org.jenkinsci.plugins.oneshot.OneShotProvisioner;
import org.jenkinsci.plugins.oneshot.OneShotSlave;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.kohsuke.stapler.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Allows jobs to specify a label of the form 'ssh-direct:{host}:{user}:{password}:{javaPath}:{workDir}'
 * Which uses the ssh-slaves and one-shot-executor plugins to create a one-shot slave on the provided
 * host for the duration of the job. Mostly useful for running jobs on transient VMs created (and
 * destroyed) as part of a pipeline job.
 */
@Extension
public class OneShotSshDirectProvisioner extends OneShotProvisioner<OneShotSlave> {

    private static final Logger LOG = LoggerFactory.getLogger(OneShotSshDirectProvisioner.class);

    private static final String LABEL_PREFIX = "ssh-direct:";

    @DataBoundConstructor
    public OneShotSshDirectProvisioner() {
    }

    /**
     * Jenkins labels can have complex forms that are parsed by Jenkins into multiple 'atoms' with possible
     * boolean logic to combine them (e.g. select a host labeled SunOS _and_ labeled x86, but not labeld do_not_use).
     * These complex forms do not really make sense in combination with this plugin, but we make a best effort attempt
     * to only consider Labels which match exactly one LabelAtom of the expected form (i.e. "ssh-direct:...").
     */
    private Optional<LabelAtom> getRelevantAtom(Label label) {
        Set<LabelAtom> atoms = new HashSet<>(label.listAtoms());
        Iterator<LabelAtom> iter = atoms.iterator();
        while (iter.hasNext()) {
            if (!iter.next().getDisplayName().startsWith(LABEL_PREFIX)) {
                iter.remove();
            }
        }

        iter = atoms.iterator();
        while (iter.hasNext()) {
            if (!label.matches(Collections.singleton(iter.next()))) {
                iter.remove();
            }
        }

        if (atoms.size() != 1) {
            return Optional.absent();
        } else {
            return Optional.of(Iterables.getOnlyElement(atoms));
        }
    }

    @Override
    protected boolean usesOneShotExecutor(Queue.Item item) {
        try {
        Label label = item.getAssignedLabel();
            if (label == null) {
                return false;
            }
            return getRelevantAtom(item.getAssignedLabel()).isPresent();
        } catch (Throwable t) {
            LOG.error(t.toString(), t);
            throw t;
        }
    }

    @Override
    public boolean canRun(Queue.Item item) {
        return usesOneShotExecutor(item);
    }

    @Override
    public OneShotSlave prepareExecutorFor(Queue.BuildableItem buildableItem) throws Exception {
        LabelAtom atom = getRelevantAtom(buildableItem.getAssignedLabel()).get();

        String[] parts = atom.getDisplayName().split(":");
        if (parts.length != 7) {
            throw new IllegalStateException("label does not match expected form: " + atom.getDisplayName());
        }

        String host = parts[1];
        final String username = parts[2];
        final String password = parts[3];
        String javaPath = parts[4];
        String jenkinsdir = parts[5];
        String launcherPrefix = parts[6];

        StandardUsernameCredentials creds = new SimpleUsernamePasswordCredentials(username, password);
        SSHLauncher launcher = new SSHLauncher(host, 22, creds, "", javaPath, launcherPrefix + " ", "", 5000, 5, 5000) {
            /**
             * This function is a shim to work around JENKINS-37115.
             *
             * Summary: The one-shot-executor plugin has not properly implemented piping the output of the
             * 'launcher' passed to OneShotSlave to the Job's console output when run in the context of a pipeline
             * job. Having this output however, is very valuable as it can often contain information about network
             * issues.
             *
             * The TaskListener passed in to this method will be TaskListener.NULL unless we modify it.
             *
             * While the one-shot-executor plugin is working on coming up with a more supported interface for
             * getting access to the correct TaskListener we will attempt to hackily pull it out of the pipeline
             * plugin's internals. The intent is to make a best-effort attempt to set the 'listener', but still
             * continue if we encounter any issues (e.g. the pipele plugin changes it's internal variable names).
             */
            @Override
            public synchronized void launch(SlaveComputer computer, TaskListener listener) throws InterruptedException {
                SubTask task = Executor.currentExecutor().getCurrentExecutable().getParent();
                if (task instanceof ExecutorStepExecution.PlaceholderTask) {
                    ExecutorStepExecution.PlaceholderTask ptask = (ExecutorStepExecution.PlaceholderTask) task;
                    try {
                        Field f = ExecutorStepExecution.PlaceholderTask.class.getDeclaredField("context");
                        f.setAccessible(true);
                        StepContext context = (StepContext) f.get(ptask);
                        listener = context.get(TaskListener.class);
                    } catch (NoSuchFieldException | IllegalAccessException | IOException e) {
                        LOG.error(e.toString(), e);
                    }
                }
                super.launch(computer, listener);
            }
        };
        return new OneShotSlave("this is a test", jenkinsdir, launcher, Charset.defaultCharset());
    }

    private static final class SimpleUsernamePasswordCredentials implements StandardUsernamePasswordCredentials {
        private final Secret password;
        private final String username;

        private SimpleUsernamePasswordCredentials(String username, String password) {
            this.username = Preconditions.checkNotNull(username);
            this.password = Secret.fromString(password);
        }

        @NonNull
        @Override
        public Secret getPassword() {
            return password;
        }

        @NonNull
        @Override
        public String getDescription() {
            return username;
        }

        @NonNull
        @Override
        public String getId() {
            return username;
        }

        @NonNull
        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public CredentialsScope getScope() {
            return CredentialsScope.GLOBAL;
        }

        @NonNull
        @Override
        public CredentialsDescriptor getDescriptor() {
            return null;
        }
    }
}
