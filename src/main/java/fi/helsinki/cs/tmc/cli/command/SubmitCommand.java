package fi.helsinki.cs.tmc.cli.command;

import com.google.common.base.Optional;
import fi.helsinki.cs.tmc.cli.backend.CourseInfo;
import fi.helsinki.cs.tmc.cli.backend.CourseInfoIo;
import fi.helsinki.cs.tmc.cli.backend.TmcUtil;
import fi.helsinki.cs.tmc.cli.core.AbstractCommand;
import fi.helsinki.cs.tmc.cli.core.CliContext;
import fi.helsinki.cs.tmc.cli.core.Command;
import fi.helsinki.cs.tmc.cli.io.Color;
import fi.helsinki.cs.tmc.cli.io.ColorUtil;
import fi.helsinki.cs.tmc.cli.io.Io;
import fi.helsinki.cs.tmc.cli.io.WorkDir;
import fi.helsinki.cs.tmc.cli.shared.ExerciseUpdater;
import fi.helsinki.cs.tmc.cli.shared.FeedbackHandler;
import fi.helsinki.cs.tmc.cli.shared.ResultPrinter;

import fi.helsinki.cs.tmc.core.domain.Course;
import fi.helsinki.cs.tmc.core.domain.Exercise;
import fi.helsinki.cs.tmc.core.domain.Organization;
import fi.helsinki.cs.tmc.core.domain.submission.FeedbackQuestion;
import fi.helsinki.cs.tmc.core.domain.submission.SubmissionResult;

import fi.helsinki.cs.tmc.core.holders.TmcSettingsHolder;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Command(name = "submit", desc = "Submit exercises")
public class SubmitCommand extends AbstractCommand {

    private static final Logger logger = LoggerFactory.getLogger(SubmitCommand.class);

    private CliContext ctx;
    private Io io;
    private boolean showAll;
    private boolean showDetails;
    private boolean filterUncompleted;
    private static int API_VERSION = 8;
    private Path courseInfoFile;

    @Override
    public void getOptions(Options options) {
        options.addOption("a", "all", false, "Show all test results");
        options.addOption("d", "details", false, "Show detailed error message");
        options.addOption(
                "c", "completed", false, "Filter out exercises that haven't been locally tested");
    }

    @Override
    public void run(CliContext context, CommandLine args) {
        this.ctx = context;
        this.io = ctx.getIo();
        WorkDir workDir = ctx.getWorkDir();

        String[] exercisesFromArgs = parseArgs(args);
        if (exercisesFromArgs == null) {
            return;
        }

        if (!ctx.checkIsLoggedIn(false, true)) {
            return;
        }

        if (exercisesFromArgs.length == 0 && workDir.getExercises().size() != 1) {
            io.println("Please give exercise to submit as argument");
            return;
        }

        for (String exercise : exercisesFromArgs) {
            if (!workDir.addPath(exercise)) {
                io.println("Error: " + exercise + " is not a valid exercise.");
                return;
            }
        }

        CourseInfo info = ctx.getCourseInfo();
        Course currentCourse = info.getCourse();
        if (currentCourse == null) {
            return;
        }

        courseInfoFile = workDir.getConfigFile();

        if (apiUrlIsOutdated(currentCourse)) {
            updateCourseAndExercises();
        }

        List<Exercise> exercises;
        if (filterUncompleted) {
            workDir.addPath(workDir.getCourseDirectory());
            exercises = workDir.getExercises(true, true);
        } else {
            exercises = workDir.getExercises();
        }

        if (exercises.isEmpty()) {
            if (filterUncompleted && workDir.getCourseDirectory() != null) {
                io.println("No locally tested exercises.");
                return;
            }
            io.println("No exercises specified.");
            return;
        }


        Color color1 = ctx.getColorProperty("testresults-left", ctx.getApp());
        Color color2 = ctx.getColorProperty("testresults-right", ctx.getApp());
        ResultPrinter resultPrinter =
                new ResultPrinter(io, this.showDetails, this.showAll, color1, color2);

        boolean isOnlyExercise = (exercises.size() == 1);
        List<Exercise> submitExercises = exercises;
        List<List<FeedbackQuestion>> feedbackLists = new ArrayList<>();
        List<String> exercisesWithFeedback = new ArrayList<>();
        List<URI> feedbackUris = new ArrayList<>();

        for (Exercise exercise : submitExercises) {
            this.ctx.getAnalyticsFacade().saveAnalytics(exercise, "submit");
            io.println(ColorUtil.colorString("Submitting: " + exercise.getName(), Color.YELLOW));
            if (exercise.hasDeadlinePassed()) {
                logger.warn("Tried to submit exercise " + exercise.getName() + " after deadline.");
                io.errorln("Deadline has passed for this exercise at " + exercise.getDeadline());
                return;
            }
            SubmissionResult result = TmcUtil.submitExercise(ctx, exercise);
            if (result == null) {
                io.errorln("Submission failed.");
                if (!isOnlyExercise) {
                    io.errorln("Try to submit exercises one by one.");
                }
                return;
            }

            resultPrinter.printSubmissionResult(result, isOnlyExercise);

            exercise.setAttempted(true);
            if (result.getStatus() == SubmissionResult.Status.OK) {
                exercise.setCompleted(true);
            }

            List<FeedbackQuestion> feedback = result.getFeedbackQuestions();
            if (feedback != null && feedback.size() > 0) {
                feedbackLists.add(feedback);
                exercisesWithFeedback.add(exercise.getName());
                feedbackUris.add(URI.create(result.getFeedbackAnswerUrl()));
            }
            io.println();
        }
        if (!isOnlyExercise) {
            resultPrinter.printTotalExerciseResults();
        }

        updateCourseJson(submitExercises, info);
        checkForExerciseUpdates(currentCourse);
        sendFeedbacks(feedbackLists, exercisesWithFeedback, feedbackUris);
    }

    private void sendFeedbacks(List<List<FeedbackQuestion>> feedbackLists, List<String> exercisesWithFeedback, List<URI> feedbackUris) {
        for (int i = 0; i < exercisesWithFeedback.size(); i++) {
            if (io.readConfirmation(
                    "Send feedback for " + exercisesWithFeedback.get(i) + "?", true)) {
                FeedbackHandler fbh = new FeedbackHandler(ctx);
                boolean success = fbh.sendFeedback(feedbackLists.get(i), feedbackUris.get(i));
                if (success) {
                    io.println("Feedback sent.");
                } else {
                    io.errorln("Failed to send feedback.");
                }
            }
        }
    }

    /**
     * Fetch updated exercise statuses from server and update course JSON file accordingly.
     */
    private void updateCourseJson(
            List<Exercise> submittedExercises, CourseInfo courseInfo) {

        List<Exercise> exercises = TmcUtil.getCourseExercises(ctx);
        if (exercises == null) {
            io.println(
                    "Failed to update config file for course " + courseInfo.getCourseName());
            return;
        }
        for (Exercise submitted : submittedExercises) {
            java.util.Optional<Exercise> ex = exercises.stream().filter(e -> e.getName().equals(submitted.getName())).findFirst();
            if (!ex.isPresent()) {
                io.println(
                        "Failed to update config file for exercise "
                                + submitted.getName()
                                + ". The exercise doesn't exist in server anymore.");
                continue;
            }
            Exercise updatedEx = ex.get();
            if (updatedEx.isCompleted()) {
                if (courseInfo.getLocalCompletedExercises().contains(updatedEx.getName())) {
                    courseInfo.getLocalCompletedExercises().remove(updatedEx.getName());
                }
            }
            courseInfo.replaceOldExercise(updatedEx);
        }
        CourseInfoIo.save(courseInfo, courseInfoFile);
    }

    private void checkForExerciseUpdates(Course course) {
        ExerciseUpdater exerciseUpdater = new ExerciseUpdater(ctx, course);
        if (!exerciseUpdater.updatesAvailable()) {
            return;
        }

        int total = 0;
        String msg = "";
        if (exerciseUpdater.newExercisesAvailable()) {
            int count = exerciseUpdater.getNewExercises().size();
            String plural = count > 1 ? "s" : "";
            msg += count + " new exercise" + plural + " available!\n";
            total += count;
        }

        if (exerciseUpdater.updatedExercisesAvailable()) {
            int count = exerciseUpdater.getUpdatedExercises().size();
            String plural = count > 1 ? "s have" : " has";
            msg += count + " exercise" + plural + " been changed on TMC server.\n";
            total += count;
        }
        msg += "Use 'tmc update' to download " + (total > 1 ? "them." : "it.");

        io.println();
        io.println(ColorUtil.colorString(msg, Color.YELLOW));
    }

    private boolean apiUrlIsOutdated(Course course) {
        return !course.getDetailsUrl().toString().contains("v" + API_VERSION);
    }

    private void updateCourseAndExercises() {
        // This is a patch to migrate away from api 7 urls
        // as some exercises have been downloaded before the new api
        String oldDetailsUrl = ctx.getCourseInfo().getCourse().getDetailsUrl().toString();
        String updatedUrl = getUpdatedDetailsUrl(oldDetailsUrl);
        try {
            ctx.getCourseInfo().getCourse().setDetailsUrl(new URI(updatedUrl));
        } catch (URISyntaxException e) {
            logger.error("Could not update details url for course " + ctx.getCourseInfo().getCourseName());
            return;
        }
        List<Exercise> exercises = TmcUtil.getCourseExercises(ctx);
        if (exercises == null) {
            io.println(
                    "Failed to update urls for exercises of course " + ctx.getCourseInfo().getCourseName());
            return;
        }
        ctx.getCourseInfo().getCourse().setExercises(exercises);
        CourseInfoIo.save(ctx.getCourseInfo(), courseInfoFile);
    }

    private static String getUpdatedDetailsUrl(String oldDetailsUrl) {
        if (!oldDetailsUrl.contains("/org")) {
            return oldDetailsUrl;
        }
        int iOrg = oldDetailsUrl.indexOf("/org");
        int iCourses = oldDetailsUrl.indexOf("/courses");
        String beginningPart = oldDetailsUrl.substring(0, iOrg);
        String endPart = oldDetailsUrl.substring(iCourses, oldDetailsUrl.length());
        return beginningPart + "/api/v" + API_VERSION + "/core" + endPart;
    }


    private String[] parseArgs(CommandLine args) {
        this.showAll = args.hasOption("a");
        this.showDetails = args.hasOption("d");
        this.filterUncompleted = args.hasOption("c");
        return args.getArgs();
    }
}
