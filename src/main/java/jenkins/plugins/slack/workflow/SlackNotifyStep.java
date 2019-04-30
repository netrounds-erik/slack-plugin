package jenkins.plugins.slack.workflow;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.google.common.collect.ImmutableSet;
import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Project;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.plugins.slack.CredentialsObtainer;
import jenkins.plugins.slack.Messages;
import jenkins.plugins.slack.SlackNotifier;
import jenkins.plugins.slack.SlackService;
import jenkins.plugins.slack.StandardSlackService;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;

/**
 * TODO implement these parameters for the pipeline step.
 * slackNotify(
 *  baseUrl: string,
 *  teamDomain: string,
 *  channel: string,
 *  tokenCredentialId: string,
 *  customMessage: string,
 *  includeCommits: boolean,
 *  includeTests: boolean
 * )
 */

/**
 * Workflow step to send a Slack channel build notification.
 */
public class SlackNotifyStep extends Step {

    private static final Logger logger = Logger.getLogger(SlackNotifyStep.class.getName());

    private String customMessage;
    private String token;
    private String tokenCredentialId;
    private String channel;
    private String baseUrl;
    private String teamDomain;

    private boolean botUser;
    private boolean failOnError;
    private boolean includeCommits;
    private boolean includeTests;

    public boolean getBotUser() {
        return botUser;
    }

    @DataBoundSetter
    public void setBotUser(boolean botUser) {
        this.botUser = botUser;
    }

    public boolean getFailOnError() {
        return failOnError;
    }

    @DataBoundSetter
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    public boolean getIncludeCommits() {
        return includeCommits;
    }

    @DataBoundSetter
    public void setIncludeCommits(boolean includeCommits) {
        this.includeCommits = includeCommits;
    }

    public boolean getIncludeTests() {
        return includeTests;
    }

    @DataBoundSetter
    public void setIncludeTests(boolean includeTests) {
        this.includeTests = includeTests;
    }

    public String getCustomMessage() {
        return customMessage;
    }

    @DataBoundSetter
    public void setCustomMessage(String customMessage) {
        this.customMessage = customMessage;
    }

    public String getToken() {
        return token;
    }

    @DataBoundSetter
    public void setToken(String token) {
        this.token = Util.fixEmpty(token);
    }

    public String getTokenCredentialId() {
        return tokenCredentialId;
    }

    @DataBoundSetter
    public void setTokenCredentialId(String tokenCredentialId) {
        this.tokenCredentialId = Util.fixEmpty(tokenCredentialId);
    }

    public String getChannel() {
        return channel;
    }

    @DataBoundSetter
    public void setChannel(String channel) {
        this.channel = Util.fixEmpty(channel);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = Util.fixEmpty(baseUrl);
        if (this.baseUrl != null && !this.baseUrl.isEmpty() && !this.baseUrl.endsWith("/")) {
            this.baseUrl += "/";
        }
    }

    public String getTeamDomain() {
        return teamDomain;
    }

    @DataBoundSetter
    public void setTeamDomain(String teamDomain) {
        this.teamDomain = Util.fixEmpty(teamDomain);
    }

    @DataBoundConstructor
    public SlackNotifyStep() {
    }

    @Override
    public StepExecution start(StepContext context) {
        return new SlackNotifyStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "slackNotify";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.slackNotifyStepDisplayName();
        }

        public ListBoxModel doFillTokenCredentialIdItems(@AncestorInPath Item item) {

            Jenkins jenkins = Jenkins.get();

            if (item == null && !jenkins.hasPermission(Jenkins.ADMINISTER) ||
                    item != null && !item.hasPermission(Item.EXTENDED_READ)) {
                return new StandardListBoxModel();
            }

            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withAll(lookupCredentials(
                            StringCredentials.class,
                            item,
                            ACL.SYSTEM,
                            new HostnameRequirement("*.slack.com"))
                    );
        }

        public FormValidation doCheckToken(@QueryParameter String value) {
            return FormValidation
                    .warning("Exposing your Integration Token is a security risk. Please use the Integration Token Credential ID");
        }
    }

    public static class SlackNotifyStepExecution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private transient final SlackNotifyStep step;

        SlackNotifyStepExecution(SlackNotifyStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {

            Jenkins jenkins = Jenkins.get();
            Item item = getItemForCredentials();
            SlackNotifier.DescriptorImpl slackDesc = jenkins.getDescriptorByType(SlackNotifier.DescriptorImpl.class);

            String baseUrl = step.baseUrl != null ? step.baseUrl : slackDesc.getBaseUrl();
            String teamDomain = step.teamDomain != null ? step.teamDomain : slackDesc.getTeamDomain();
            String tokenCredentialId = step.tokenCredentialId != null ? step.tokenCredentialId : slackDesc
                    .getTokenCredentialId();
            String token = step.token;
            boolean botUser = step.botUser || slackDesc.isBotUser();
            String channel = step.channel != null ? step.channel : slackDesc.getRoom();

            TaskListener listener = getContext().get(TaskListener.class);
            Objects.requireNonNull(listener, "Listener is mandatory here");

            listener.getLogger().println(Messages.slackNotifyStepValues(
                    defaultIfEmpty(baseUrl), defaultIfEmpty(teamDomain), channel, botUser,
                    defaultIfEmpty(tokenCredentialId), step.customMessage, step.includeCommits, step.includeTests));

            final String populatedToken;
            try {
                populatedToken = CredentialsObtainer.getTokenToUse(tokenCredentialId, item, token);
            } catch (IllegalArgumentException e) {
                listener.error(Messages
                        .notificationFailedWithException(e));
                return null;
            }

            SlackService slackService = getSlackService(
                    baseUrl, teamDomain, botUser, channel, false, populatedToken);

            final boolean publishSuccess = slackService.publish(step.customMessage, "good");
            if (publishSuccess) {
                return null; // This is intended since Void is not instantiable.
            } else if (step.failOnError) {
                throw new AbortException(Messages.notificationFailed());
            } else {
                listener.error(Messages.notificationFailed());
            }
            return null;
        }

        /**
         * Tries to obtain the proper Item object to provide to CredentialsProvider.
         * Project works for freestyle jobs, the parent of the Run works for pipelines.
         * In case the proper item cannot be found, null is returned, since when null is provided to CredentialsProvider,
         * it will internally use Jenkins.getInstance() which effectively only allows global credentials.
         *
         * @return the item to use for CredentialsProvider credential lookup
         */
        private Item getItemForCredentials() {
            Item item = null;
            try {
                item = getContext().get(Project.class);
                if (item == null) {
                    Run run = getContext().get(Run.class);
                    if (run != null) {
                        item = run.getParent();
                    } else {
                        item = null;
                    }
                }
            } catch (Exception e) {
                logger.log(Level.INFO, "Exception obtaining item for credentials lookup. Only global credentials will be available", e);
            }
            return item;
        }

        private String defaultIfEmpty(String value) {
            return Util.fixEmpty(value) != null ? value : Messages.slackSendStepValuesEmptyMessage();
        }

        SlackService getSlackService(String baseUrl, String team, boolean botUser, String channel, boolean replyBroadcast, String populatedToken) {
            return new StandardSlackService(baseUrl, team, botUser, channel, replyBroadcast, populatedToken);
        }
    }
}
