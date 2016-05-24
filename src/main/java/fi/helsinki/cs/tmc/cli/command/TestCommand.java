package fi.helsinki.cs.tmc.cli.command;

import fi.helsinki.cs.tmc.cli.Application;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/**
 * Class is a test command class.
 */
public class TestCommand implements Command {
    private Options options;
    private GnuParser parser;

    public TestCommand(Application app) {
        this.parser = new GnuParser();
        this.options = new Options();
        options.addOption("a", false, "testikomento");
    }

    @Override
    public String getDescription() {
        return "This is an easter egg test command.";
    }

    @Override
    public String getName() {
        return "easter-egg";
    }

    @Override
    public void run(String[] args) {
        try {
            CommandLine line = this.parser.parse(options, args);
            if (line.hasOption("a")) {
                System.out.println("Let's run easter egg with -a");
            } else {
                System.out.println("Let's run easter egg.");
            }
        } catch (ParseException exp) {
            System.err.println("Parsing failed.  Reason: " + exp.getMessage());
        }
    }
}