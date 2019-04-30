package jenkins.plugins.slack.workflow;

import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TestResult;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.plugins.slack.TokenExpander;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;


public class NotificationBuilder {

    private static final Pattern aTag = Pattern.compile("(?i)<a([^>]+)>(.+?)</a>|([{%])");
    private static final Pattern href = Pattern.compile("\\s*(?i)href\\s*=\\s*(\"([^\"]*\")|'[^']*'|([^'\">\\s]+))");
    private static final String BACK_TO_NORMAL_STATUS_MESSAGE = "Back to normal",
                                STILL_FAILING_STATUS_MESSAGE = "Still Failing",
                                SUCCESS_STATUS_MESSAGE = "Success",
                                FAILURE_STATUS_MESSAGE = "Failure",
                                ABORTED_STATUS_MESSAGE = "Aborted",
                                NOT_BUILT_STATUS_MESSAGE = "Not built",
                                UNSTABLE_STATUS_MESSAGE = "Unstable",
                                REGRESSION_STATUS_MESSAGE = "Regression",
                                UNKNOWN_STATUS_MESSAGE = "Unknown";
    private String color;
    private StringBuilder message;
    private final TokenExpander tokenExpander;
    private Run<?, ?> build;

    public NotificationBuilder(Run<?, ?> build, TokenExpander tokenExpander) {
        this.tokenExpander = tokenExpander;
        this.message = new StringBuilder();
        this.build = build;
    }

    public String getColor(){
        return color;
    }

    public NotificationBuilder appendStatusMessage() {
        message.append(this.escape(getStatusMessage(build)));
        return this;
    }

    private String getStatusMessage(Run<?, ?> r) {
        Result result = r.getParent().getLastBuild().getResult();
        Result previousResult;
        if(null != result) {
            Run<?, ?> lastBuild = r.getParent().getLastBuild();
            if (lastBuild != null) {
                Run<?, ?> previousBuild = lastBuild.getPreviousBuild();
                Run<?, ?> previousSuccessfulBuild = r.getPreviousSuccessfulBuild();
                boolean buildHasSucceededBefore = previousSuccessfulBuild != null;

                /*
                 * If the last build was aborted, go back to find the last non-aborted build.
                 * This is so that aborted builds do not affect build transitions.
                 * I.e. if build 1 was failure, build 2 was aborted and build 3 was a success the transition
                 * should be failure -> success (and therefore back to normal) not aborted -> success.
                 */
                Run<?, ?> lastNonAbortedBuild = previousBuild;
                while (lastNonAbortedBuild != null && lastNonAbortedBuild.getResult() == Result.ABORTED) {
                    lastNonAbortedBuild = lastNonAbortedBuild.getPreviousBuild();
                }


                /* If all previous builds have been aborted, then use
                 * SUCCESS as a default status so an aborted message is sent
                 */
                if (lastNonAbortedBuild == null) {
                    previousResult = Result.SUCCESS;
                } else {
                    previousResult = lastNonAbortedBuild.getResult();
                }

                /* Back to normal should only be shown if the build has actually succeeded at some point.
                 * Also, if a build was previously unstable and has now succeeded the status should be
                 * "Back to normal"
                 */
                if (result == Result.SUCCESS
                        && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                        && buildHasSucceededBefore) {
                    this.color = "good";
                    return BACK_TO_NORMAL_STATUS_MESSAGE;
                }
                if (result == Result.FAILURE && previousResult == Result.FAILURE) {
                    this.color = "critical";
                    return STILL_FAILING_STATUS_MESSAGE;
                }
                if (result == Result.SUCCESS) {
                    this.color = "good";
                    return SUCCESS_STATUS_MESSAGE;
                }
                if (result == Result.FAILURE) {
                    this.color = "bad";
                    return FAILURE_STATUS_MESSAGE;
                }
                if (result == Result.ABORTED) {
                    return ABORTED_STATUS_MESSAGE;
                }
                if (result == Result.NOT_BUILT) {
                    return NOT_BUILT_STATUS_MESSAGE;
                }
                if (result == Result.UNSTABLE) {
                    this.color = "bad";
                    return UNSTABLE_STATUS_MESSAGE;
                }
                if (lastNonAbortedBuild != null && previousResult != null && result.isWorseThan(previousResult)) {
                    this.color = "bad";
                    return REGRESSION_STATUS_MESSAGE;
                }
            }
        }
        return UNKNOWN_STATUS_MESSAGE;
    }

    public NotificationBuilder append(String string) {
        message.append(this.escape(string));
        return this;
    }

    public NotificationBuilder append(Object string) {
        message.append(this.escape(string.toString()));
        return this;
    }

    private NotificationBuilder startMessage() {
        message.append(this.escape(build.getParent().getFullDisplayName()));
        message.append(" - ");
        message.append(this.escape(build.getDisplayName()));
        message.append(" ");
        return this;
    }

    public NotificationBuilder appendOpenLink() {
        String url = DisplayURLProvider.get().getRunURL(build);
        message.append(" (<").append(url).append("|Open>)");
        return this;
    }

    public NotificationBuilder appendDuration() {
        message.append(" after ");
        String durationString;
        if(message.toString().contains(BACK_TO_NORMAL_STATUS_MESSAGE)){
            durationString = createBackToNormalDurationString();
        } else {
            durationString = build.getDurationString();
        }
        message.append(durationString);
        return this;
    }

    public NotificationBuilder appendTestSummary() {
        AbstractTestResultAction<?> action = this.build
                .getAction(AbstractTestResultAction.class);
        if (action != null) {
            int total = action.getTotalCount();
            int failed = action.getFailCount();
            int skipped = action.getSkipCount();
            message.append("\nTest Status:\n");
            message.append("\tPassed: ")
                    .append(total - failed - skipped);
            message.append(", Failed: ").append(failed);
            message.append(", Skipped: ").append(skipped);
        } else {
            message.append("\nNo Tests found.");
        }
        return this;
    }

    public NotificationBuilder appendFailedTests() {
        AbstractTestResultAction<?> action = this.build
                .getAction(AbstractTestResultAction.class);
        if (action != null) {
            int failed = action.getFailCount();
            if (failed > 0) {
                message.append("\n").append(failed).append(" Failed Tests:\n");
                for(TestResult result : action.getFailedTests()) {
                    message.append("\t").append(getTestClassAndMethod(result)).append(" after ")
                            .append(result.getDurationString()).append("\n");
                }
            }
        }
        return this;
    }

    public NotificationBuilder appendCustomMessage(Result buildResult) {
        // TODO
        return this;
    }

    private String getTestClassAndMethod(TestResult result) {
        String fullDisplayName = result.getFullDisplayName();

        if (StringUtils.countMatches(fullDisplayName, ".") > 1) {
            int methodDotIndex = fullDisplayName.lastIndexOf('.');
            int testClassDotIndex = fullDisplayName.substring(0, methodDotIndex).lastIndexOf('.');

            return fullDisplayName.substring(testClassDotIndex + 1);

        } else {
            return fullDisplayName;
        }
    }

    private String createBackToNormalDurationString(){
        // This status code guarantees that the previous build fails and has been successful before
        // The back to normal time is the time since the build first broke
        Run<?, ?> previousSuccessfulBuild = build.getPreviousSuccessfulBuild();
        if (null != previousSuccessfulBuild && null != previousSuccessfulBuild.getNextBuild()) {
            Run<?, ?> initialFailureAfterPreviousSuccessfulBuild = previousSuccessfulBuild.getNextBuild();
            if (initialFailureAfterPreviousSuccessfulBuild != null) {
                long initialFailureStartTime = initialFailureAfterPreviousSuccessfulBuild.getStartTimeInMillis();
                long initialFailureDuration = initialFailureAfterPreviousSuccessfulBuild.getDuration();
                long initialFailureEndTime = initialFailureStartTime + initialFailureDuration;
                long buildStartTime = build.getStartTimeInMillis();
                long buildDuration = build.getDuration();
                long buildEndTime = buildStartTime + buildDuration;
                long backToNormalDuration = buildEndTime - initialFailureEndTime;
                return Util.getTimeSpanString(backToNormalDuration);
            }
        }
        return null;
    }

    private String escapeCharacters(String string) {
        string = string.replace("&", "&amp;");
        string = string.replace("<", "&lt;");
        string = string.replace(">", "&gt;");

        return string;
    }

    private String[] extractReplaceLinks(Matcher aTag, StringBuffer sb) {
        int size = 0;
        List<String> links = new ArrayList<>();
        while (aTag.find()) {
            String firstGroup = aTag.group(1);
            if (firstGroup != null) {
                Matcher url = href.matcher(firstGroup);
                if (url.find()) {
                    String escapeThis = aTag.group(3);
                    if (escapeThis != null) {
                        aTag.appendReplacement(sb, String.format("{%s}", size++));
                        links.add(escapeThis);
                    } else {
                        aTag.appendReplacement(sb, String.format("{%s}", size++));
                        links.add(String.format("<%s|%s>", url.group(1).replaceAll("\"", ""), aTag.group(2)));
                    }
                }
            } else {
                String escapeThis = aTag.group(3);
                aTag.appendReplacement(sb, String.format("{%s}", size++));
                links.add(escapeThis);
            }
        }
        aTag.appendTail(sb);
        return links.toArray(new String[size]);
    }

    public String escape(String string) {
        StringBuffer pattern = new StringBuffer();
        String[] links = extractReplaceLinks(aTag.matcher(string), pattern);
        return MessageFormat.format(escapeCharacters(pattern.toString()), links);
    }

    public String toString() {
        return message.toString();
    }
}
