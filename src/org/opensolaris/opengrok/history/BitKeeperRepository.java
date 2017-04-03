/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

package org.opensolaris.opengrok.history;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jrcs.rcs.InvalidVersionNumberException;
import org.apache.commons.jrcs.rcs.Version;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.logger.LoggerFactory;
import org.opensolaris.opengrok.util.Executor;

/**
 * Access to a BitKeeper repository.
 *
 * @author James Service  {@literal <jas2701@googlemail.com>}
 */
public class BitKeeperRepository extends Repository {

    private static final Logger LOGGER = LoggerFactory.getLogger(BitKeeperRepository.class);

    private static final long serialVersionUID = 1L;
    /**
     * The property name used to obtain the client command for this repository.
     */
    public static final String CMD_PROPERTY_KEY = "org.opensolaris.opengrok.history.BitKeeper";
    /**
     * The command to use to access the repository if none was given explicitly.
     */
    public static final String CMD_FALLBACK = "bk";
    /**
     * The output format specification for log commands.
     */
    private static final String LOG_DSPEC =
            "D :DPN:\\t:REV:\\t:D_: :T: GMT:TZ:\\t:USER:$if(:RENAME:){\\t:DPN|PARENT:}\\n$each(:C:){C (:C:)\\n}";
    /**
     * The output format specification for tags commands. Versions 7.3 and greater.
     */
    private static final String TAG_DSPEC = "D :REV:\\t:D_: :T: GMT:TZ:\\n$each(:TAGS:){T (:TAGS:)\\n}";
    /**
     * The output format specification for tags commands. Versions 7.2 and less.
     */
    private static final String TAG_DSPEC_OLD = "D :REV:\\t:D_: :T: GMT:TZ:\\n$each(:TAG:){T (:TAG:)\\n}";
    /**
     * The output format specification for tags commands. Versions 7.2 and less.
     */
    private static final Version NEW_DSPEC_VERSION = new Version(7, 3);
    /*
     * Using a dspec not only makes it easier to parse, but also means we don't get tripped up by any system-wide
     * non-default dspecs on the box we are running on.
     */
    /**
     * Pattern to parse a version number from output of bk --version.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("BitKeeper version is .*-(\\d(\\.\\d)*)");

    /**
     * The version of the BitKeeper executable. This affects the correct dspec to use for tags.
     */
    private Version version = null;

    /**
     * Constructor to construct the thing to be constructed.
     */
    public BitKeeperRepository() {
        type = "BitKeeper";
        datePatterns = new String[] { "yyyy-MM-dd HH:mm:ss z" };
    }

    /**
     * Updates working and version member variables by running bk --version.
     */
    private void ensureVersion() {
        if (working == null) {
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            final Executor exec = new Executor(new String[] { RepoCommand, "--version" });
            if (exec.exec(false) == 0) {
                working = Boolean.TRUE;
                final Matcher matcher = VERSION_PATTERN.matcher(exec.getOutputString());
                if (matcher.find()) {
                    try {
                        version = new Version(matcher.group(1));
                    } catch (final InvalidVersionNumberException e) {
                        assert false : "Failed to parse a version number.";
                    }
                }
            } else {
                working = Boolean.FALSE;
            }
            if (version == null) {
                version = new Version(0, 0);
            }
        }
    }

    /**
     * Returns whether file represents a BitKeeper repository. A BitKeeper repository has a folder named .bk at its
     * source root.
     *
     * @return ret a boolean denoting whether it is or not
     */
    @Override
    boolean isRepositoryFor(File file) {
        if (file.isDirectory()) {
            final File f = new File(file, ".bk");
            return f.exists() && f.isDirectory();
        }
        return false;
    }

    /**
     * Returns whether the BitKeeper command is working.
     *
     * @return working a boolean denoting whether it is or not
     */
    @Override
    public boolean isWorking() {
        ensureVersion();
        return working.booleanValue();
    }

    /**
     * Returns the version of the BitKeeper executable.
     *
     * @return version a Version object
     */
    public Version getVersion() {
        ensureVersion();
        return version;
    }

    /**
     * Implementation of abstract method determineBranch. BitKeeper doesn't really have branches as such.
     *
     * @return null
     */
    @Override
    String determineBranch() throws IOException {
        return null;
    }

    /**
     * Return the first listed pull parent of this repository BitKeeper can have multiple push parents and pul parents.
     *
     * @return parent a string denoting the parent, or null.
     */
    @Override
    String determineParent() throws IOException {
        final File directory = new File(directoryName);

        final ArrayList<String> argv = new ArrayList<String>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("parent");
        argv.add("-1il");

        final Executor executor = new Executor(argv, directory);
        final int rc = executor.exec(false);
        final String parent = executor.getOutputString().trim();
        if (rc == 0) {
            return parent;
        } else if (parent.equals("This repository has no pull parent.")) {
            return null;
        } else {
            throw new IOException(executor.getErrorString());
        }
    }

    /* History Stuff */
    /*
     * BitKeeper has independent revisions for its individual files like CVS, but also provides changesets, which is an
     * atomic commit of a group of deltas to files. Changesets have their own revision numbers.
     *
     * When constructing a history then, we therefore have a choice of whether to go by file revisions, or changeset
     * revisions. It seemed like doing it by changeset revisions would be both a) more difficult, and b) not in tune
     * with how BitKeeper is actually used (although, in the interest of full disclosure, I have only been using it for
     * a month).
     */

    /**
     * Returns whether BitKeeper has history for its directories.
     *
     * @return false
     */
    @Override
    boolean hasHistoryForDirectories() {
        return false;
    }

    /**
     * Returns whether BitKeeper has history for a file.
     *
     * @return ret a boolean denoting whether it does or not
     */
    @Override
    public boolean fileHasHistory(File file) {
        final File absolute = file.getAbsoluteFile();
        final File directory = absolute.getParentFile();
        final String basename = absolute.getName();

        final ArrayList<String> argv = new ArrayList<String>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("files");
        argv.add(basename);

        final Executor executor = new Executor(argv, directory);
        if (executor.exec(true) != 0) {
            LOGGER.log(Level.SEVERE, "Failed to check file: {0}", executor.getErrorString());
            return false;
        }

        return executor.getOutputString().trim().equals(basename);
    }

    /**
     * Construct a History for a file in this repository.
     *
     * @param file a file in the repository
     * @return history a history object
     */
    @Override
    History getHistory(File file) throws HistoryException {
        return getHistory(file, null);
    }

    /**
     * Construct a History for a file in this repository.
     *
     * @param file a file in the repository
     * @param sinceRevision omit history from before, and including, this revision
     * @return history a history object
     */
    @Override
    History getHistory(File file, String sinceRevision) throws HistoryException {
        final File absolute = file.getAbsoluteFile();
        final File directory = absolute.getParentFile();
        final String basename = absolute.getName();

        final ArrayList<String> argv = new ArrayList<String>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("log");
        if (sinceRevision != null) {
            argv.add("-r" + sinceRevision + "..");
        }
        argv.add("-d" + LOG_DSPEC);
        argv.add(basename);

        final Executor executor = new Executor(argv, directory);
        final BitKeeperHistoryParser parser = new BitKeeperHistoryParser(datePatterns[0]);
        if (executor.exec(true, parser) != 0) {
            throw new HistoryException(executor.getErrorString());
        }

        final RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        final History history = parser.getHistory();

        // Assign tags to changesets they represent
        // We don't need to check if this repository supports tags,
        // because we know it :-)
        if (env.isTagsEnabled()) {
            assignTagsInHistory(history);
        }

        return history;
    }

    /**
     * Return an InputStream of the content of a given file at a given revision.
     *
     * @param parent the directory the file is in
     * @param basename the basename of the file
     * @param revision revision, or null for latest
     * @return output an input stream
     */
    @Override
    public InputStream getHistoryGet(String parent, String basename, String revision) {
        final File directory = new File(parent).getAbsoluteFile();

        final ArrayList<String> argv = new ArrayList<String>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("get");
        argv.add("-p");
        if (revision != null) {
            argv.add("-r" + revision);
        }
        argv.add(basename);

        final Executor executor = new Executor(argv, directory);
        if (executor.exec(true) != 0) {
            LOGGER.log(Level.SEVERE, "Failed to get history: {0}", executor.getErrorString());
            return null;
        }

        return executor.getOutputStream();
    }

    /* Annotation Stuff */

    /**
     * Returns whether BitKeeper has annotation for a file. It does if it has history for the file.
     *
     * @return ret a boolean denoting whether it does or not
     */
    @Override
    public boolean fileHasAnnotation(File file) {
        return fileHasHistory(file);
    }

    /**
     * Annotate the specified file/revision. The options `-aur` to `bk annotate` specify that bitkeeper will output the
     * last user to edit the line, the last revision the line was edited, and then the line itself, each separated by a
     * hard tab.
     *
     * @param file file to annotate
     * @param revision revision to annotate, or null for latest
     * @return annotation file annotation
     */
    @Override
    public Annotation annotate(File file, String revision) throws IOException {
        final File absolute = file.getCanonicalFile();
        final File directory = absolute.getParentFile();
        final String basename = absolute.getName();

        final ArrayList<String> argv = new ArrayList<String>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("annotate");
        argv.add("-aur");
        if (revision != null) {
            argv.add("-r" + revision);
        }
        argv.add(basename);

        final Executor executor = new Executor(argv, directory);
        final BitKeeperAnnotationParser parser = new BitKeeperAnnotationParser(basename);
        if (executor.exec(true, parser) != 0) {
            throw new IOException(executor.getErrorString());
        } else {
            return parser.getAnnotation();
        }
    }

    /* Tag Stuff */

    /**
     * Returns whether a set of tags should be constructed up front. BitKeeper tags changesets, not files, so yes.
     *
     * @return true
     */
    @Override
    boolean hasFileBasedTags() {
        return true;
    }

    /**
     * Returns the version of the BitKeeper executable.
     *
     * @return version a Version object
     */
    private String getTagDspec() {
        if (NEW_DSPEC_VERSION.compareVersions(getVersion()) <= 0) {
            return TAG_DSPEC;
        } else {
            return TAG_DSPEC_OLD;
        }
    }

    /**
     * Constructs a set of tags up front.
     *
     * @param directory the repository directory
     */
    @Override
    public void buildTagList(File directory) {
        final ArrayList<String> argv = new ArrayList<String>();
        argv.add("bk");
        argv.add("tags");
        argv.add("-d" + getTagDspec());

        final Executor executor = new Executor(argv, directory);
        final BitKeeperTagParser parser = new BitKeeperTagParser(datePatterns[0]);
        executor.exec(true, parser);
        tagList = parser.getEntries();
    }

    /* Update Stuff */

    @Override
    public void update() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}