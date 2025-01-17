package mediathek;

import com.google.common.base.Stopwatch;
import com.jidesoft.utils.ThreadCheckingRepaintManager;
import com.zaxxer.sansorm.SansOrm;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.control.Alert;
import javafx.stage.Modality;
import mediathek.config.Config;
import mediathek.config.Daten;
import mediathek.config.Konstanten;
import mediathek.config.MVConfig;
import mediathek.daten.DatenFilm;
import mediathek.daten.PooledDatabaseConnection;
import mediathek.gui.dialog.DialogStarteinstellungen;
import mediathek.javafx.tool.JavaFxUtils;
import mediathek.mac.MediathekGuiMac;
import mediathek.mainwindow.MediathekGui;
import mediathek.tool.*;
import mediathek.windows.MediathekGuiWindows;
import mediathek.x11.MediathekGuiX11;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.filter.ThresholdFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import picocli.CommandLine;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Optional;

public class Main {
    private static final String MAC_SYSTEM_PROPERTY_APPLE_LAF_USE_SCREEN_MENU_BAR = "apple.laf.useScreenMenuBar";

    private static final Logger logger = LogManager.getLogger(Main.class);

    /**
     * Ensures that old film lists in .mediathek directory get deleted because they were moved to
     * ~/Library/Caches/MediathekView
     * In portable mode we MUST NOT delete the files.
     */
    private static void cleanupOsxFiles() {
        if (!Config.isPortableMode()) {
            try {
                var oldFilmList = Paths.get(Daten.getSettingsDirectory_String(), Konstanten.JSON_DATEI_FILME);
                Files.deleteIfExists(oldFilmList);
            } catch (IOException ignored) {
            }
        }
    }

    private static void printArguments(final String... aArguments) {
        for (String argument : aArguments) {
            logger.info("Startparameter: {}", argument);
        }
    }

    private static void setupLogging() {
        final var loggerContext = (LoggerContext) LogManager.getContext(false);
        final var config = loggerContext.getConfiguration();
        final String path;

        if (!Config.isPortableMode())
            path = Daten.getSettingsDirectory_String() + "/mediathekview.log";
        else
            path = Config.baseFilePath + "/mediathekview.log"; //TODO maybe resolve is better in this case


        final PatternLayout consolePattern;
        if (Config.isEnhancedLoggingEnabled() || Config.isDebugModeEnabled()) {
            consolePattern = PatternLayout.newBuilder().withPattern("[%-5level] [%t] %c - %msg%n").build();
        }
        else {
            consolePattern = PatternLayout.newBuilder().withPattern(". %msg%n").build();
        }

        var consoleAppender = ConsoleAppender.createDefaultAppenderForLayout(consolePattern);
        //for normal users only show INFO and higher messages
        if (!Config.isEnhancedLoggingEnabled() && !Config.isDebugModeEnabled()) {
            final var thresholdFilter = ThresholdFilter.createFilter(Level.INFO, Filter.Result.ACCEPT, Filter.Result.DENY);
            consoleAppender.addFilter(thresholdFilter);
        }
        consoleAppender.start();

        var fileAppenderBuilder = FileAppender.newBuilder()
                .setName("LogFile")
                .withAppend(false)
                .withFileName(path)
                .setLayout(PatternLayout.newBuilder().withPattern("%-5p %d  [%t] %C{2} (%F:%L) - %m%n").build())
                //.setLayout(PatternLayout.newBuilder().withPattern("[%-5level] %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c - %msg%n").build())
                .setConfiguration(config);

        //regular users may have DEBUG output in log file but not TRACE
        if (!Config.isEnhancedLoggingEnabled() && !Config.isDebugModeEnabled()) {
            final var thresholdFilter = ThresholdFilter.createFilter(Level.DEBUG, Filter.Result.ACCEPT, Filter.Result.DENY);
            fileAppenderBuilder.setFilter(thresholdFilter);
        }

        FileAppender fileAppender = fileAppenderBuilder.build();
        fileAppender.start();
        config.addAppender(fileAppender);

        AsyncAppender asyncAppender = AsyncAppender.newBuilder()
                .setName("Async")
                .setAppenderRefs(new AppenderRef[] {AppenderRef.createAppenderRef(fileAppender.getName(), null, null)})
                .setConfiguration(config)
                .setIncludeLocation(true)
                .setBlocking(false)
                .build();

        asyncAppender.start();
        config.addAppender(asyncAppender);

        final var rootLogger = loggerContext.getRootLogger();
        rootLogger.setLevel(Level.TRACE);
        rootLogger.addAppender(consoleAppender);
        rootLogger.addAppender(asyncAppender);

        loggerContext.updateLoggers();
    }

    private static void setupPortableMode() {
        Stopwatch stopwatch = Stopwatch.createStarted();
        var portableMode = Config.isPortableMode();
        logger.info("Portable Mode: {}", portableMode);

        if (portableMode) {
            logger.trace("Configuring baseFilePath {} for portable mode", Config.baseFilePath);
            Daten.getInstance(Config.baseFilePath);
        } else {
            logger.trace("Configuring for non-portable mode");
            Daten.getInstance();
        }
        stopwatch.stop();
        logger.trace("setupPortableMode: {}", stopwatch);
    }

    /**
     * Query the class name for Nimbus L&F.
     *
     * @return the class name for Nimbus, otherwise return the system default l&f class name.
     */
    private static String queryNimbusLaFName() {
        String systemLaF = UIManager.getSystemLookAndFeelClassName();

        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    systemLaF = info.getClassName();
                    break;
                }
            }
        } catch (Exception e) {
            systemLaF = UIManager.getSystemLookAndFeelClassName();
        }

        return systemLaF;
    }

    /**
     * Set the look and feel for various OS.
     * On macOS, don´t change anything as the JVM will use the native UI L&F for swing.
     * On windows, use the system windows l&f for swing.
     * On Linux, use Nimbus l&f which is more modern than Metal.
     * <p>
     * One can override the L&F stuff for non-macOS by supplying -Dswing.defaultlaf=class_name and the class name on the CLI.
     */
    private static void setSystemLookAndFeel() {
        //don´t set L&F on macOS...
        if (SystemUtils.IS_OS_MAC_OSX)
            return;

        final String laf = System.getProperty("swing.defaultlaf");
        if (laf == null || laf.isEmpty()) {
            //only set L&F if there was no define on CLI
            logger.trace("L&F property is empty, setting L&F");
            //use system for windows and macOS
            String systemLaF = UIManager.getSystemLookAndFeelClassName();
            //on linux, use more modern Nimbus L&F...
            if (SystemUtils.IS_OS_LINUX) {
                systemLaF = queryNimbusLaFName();
            }

            //set the L&F...
            try {
                UIManager.setLookAndFeel(systemLaF);
            } catch (IllegalAccessException | InstantiationException | UnsupportedLookAndFeelException | ClassNotFoundException e) {
                logger.error("L&F error: ", e);
            }
        }
    }

    private static void setupEnvironmentProperties() {
        System.setProperty("file.encoding", "UTF-8");

        //enable full strength crypto if not already done
        Security.setProperty("crypto.policy", "unlimited");
    }

    private static void printVersionInformation() {
        final var formatter = FastDateFormat.getInstance("dd.MM.yyyy HH:mm:ss");

        logger.debug("=== Java Information ===");
        logger.info("Programmstart: {}", formatter.format(Log.startZeit));
        //Version
        logger.info("Version: {}", Konstanten.MVVERSION);

        final long maxMem = Runtime.getRuntime().maxMemory();
        logger.debug("maxMemory: {} MB", maxMem / FileUtils.ONE_MB);

        logger.debug("Java:");
        final String[] java = Functions.getJavaVersion();
        for (String ja : java) {
            logger.debug(ja);
        }
        logger.debug("===");
    }

    /**
     * @param args the command line arguments
     */
    public static void main(final String... args) {
        setupEnvironmentProperties();

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Diese Version von MediathekView unterstützt keine Kommandozeilenausführung.");
            System.exit(1);
        }

        CommandLine cmd = new CommandLine(Config.class);
        try {
            var parseResult = cmd.parseArgs(args);
            if (parseResult.isUsageHelpRequested()) {
                cmd.usage(System.out);
                System.exit(cmd.getCommandSpec().exitCodeOnUsageHelp());
            }

            Config.setPortableMode(parseResult.hasMatchedPositional(0));
            setupLogging();
            setupPortableMode();

            printVersionInformation();
            printArguments(args);
        } catch (CommandLine.ParameterException ex) {
            cmd.getErr().println(ex.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, cmd.getErr())) {
                ex.getCommandLine().usage(cmd.getErr());
            }
            System.exit(cmd.getCommandSpec().exitCodeOnInvalidInput());
        } catch (Exception ex) {
            logger.error("Command line parse error:", ex);
            System.exit(cmd.getCommandSpec().exitCodeOnExecutionException());
        }

        printDirectoryPaths();

        initializeJavaFX();

        checkMemoryRequirements();

        installSingleInstanceHandler();

        setSystemLookAndFeel();

        splashScreen = Optional.of(new SplashScreen());
        splashScreen.ifPresent(SplashScreen::show);

        loadConfigurationData();

        Daten.getInstance().launchHistoryDataLoading();

        deleteDatabase();

        if (MemoryUtils.isLowMemoryEnvironment()) {
            setupDatabase();
            DatenFilm.Database.initializeDatabase();
        }

        startGuiMode();
    }

    @SuppressWarnings("unused")
    private static void initializeJavaFX() {
        //JavaFX stuff
        Platform.setImplicitExit(false);
        //necessary to init JavaFX before loading config data
        var dummy = new JFXPanel();
    }

    private static void loadConfigurationData() {
        var daten = Daten.getInstance();

        /*JavaFxUtils.invokeInFxThreadAndWait(() -> {
            var btn1 = new CommandLinksDialog.CommandLinksButtonType(
                    "Add a network that is NOT in range",
                    "That will show you a list of networks that are currently NOT available and lets you connect to one.",
                    false);

            List<CommandLinksDialog.CommandLinksButtonType> links = Arrays.asList(
                    new CommandLinksDialog.CommandLinksButtonType(
                            "Add a network that is in range",
                            "this shows you a list of networks that are currently available and lets you connect to one.",
                            false),
                    new CommandLinksDialog.CommandLinksButtonType(
                            "Manually create a network profile",
                            "This creates a new network profile or locates an existing one and saves it on your computer.",
                            true),
                    btn1
                    );

            CommandLinksDialog dlg = new CommandLinksDialog(links);
            dlg.setTitle("Manually connect to wireless network");
            String optionalMasthead = "Manually connect to wireless network";
            dlg.getDialogPane().setContentText(optionalMasthead);
            dlg.showAndWait().ifPresent(result -> System.out.println("Result is " + dlg.getResult()));
            System.exit(2);
        });*/

        /*JavaFxUtils.invokeInFxThreadAndWait(() -> {
            var page1 = new WizardPane() {
                @Override public void onEnteringPage(Wizard wizard) {
                    String first = (String) wizard.getSettings().get("first");
                    setContentText("Hello " + first);
                }
            };

            var page2 = new WizardPane();
            page2.setContentText("Please locate apps");

            var page3 = new WizardPane();
            page3.setContentText("Please set geographic location");
            page3.getButtonTypes().add(new ButtonType("Help", ButtonBar.ButtonData.HELP_2));

            var wizard = new Wizard();
            wizard.getSettings().put("first","MyName");
            wizard.setTitle("Our new wizard from hell");
            wizard.setFlow(new Wizard.LinearFlow(page1,page2,page3));
            wizard.showAndWait().ifPresent(r -> {
                if (r == ButtonType.FINISH) {
                    System.out.println("Wizard finished, settings: " + wizard.getSettings());
                }
            });
        });
        System.exit(2);*/

        if (!daten.allesLaden()) {
            // erster Start
            ReplaceList.init(); // einmal ein Muster anlegen, für Linux/OS X ist es bereits aktiv!
            Main.splashScreen.ifPresent(SplashScreen::close);
            //TODO replace with JavaFX dialog!!
            var dialog = new DialogStarteinstellungen(null, daten);
            dialog.setVisible(true);
            MVConfig.loadSystemParameter();
        }
    }

    public static Optional<SplashScreen> splashScreen = Optional.empty();

    private static void printDirectoryPaths() {
        logger.trace("Programmpfad: " + MVFunctionSys.getPathJar());
        logger.info("Verzeichnis Einstellungen: " + Daten.getSettingsDirectory_String());
    }

    private static void deleteDatabase() {
        if (!MemoryUtils.isLowMemoryEnvironment()) {
            //we can delete the database as it is not needed.
            try {
                final String dbLocation = PooledDatabaseConnection.getDatabaseLocation() + "mediathekview.mv.db";
                Files.deleteIfExists(Paths.get(dbLocation));
            } catch (IOException e) {
                logger.error("deleteDatabase()", e);
            }
        }
    }

    private static void setupDatabase() {
        logger.trace("setupDatabase()");
        SansOrm.initializeTxSimple(PooledDatabaseConnection.getInstance().getDataSource());
    }

    private static void installSingleInstanceHandler() {
        //prevent startup of multiple instances...
        var singleInstanceWatcher = new SingleInstance();
        if (singleInstanceWatcher.isAppAlreadyActive()) {
            JavaFxUtils.invokeInFxThreadAndWait(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle(Konstanten.PROGRAMMNAME);
                alert.setHeaderText("MediathekView wird bereits ausgeführt");
                alert.setContentText("Es dürfen nicht mehrere Programme gleichzeitig laufen.\n" +
                        "Bitte beenden Sie die andere Instanz.");
                alert.initModality(Modality.APPLICATION_MODAL);
                alert.showAndWait();
            });
            System.exit(1);
        }
    }

    private static void checkForOfficialOSXAppUse() {
        final var osxOfficialApp = System.getProperty("OSX_OFFICIAL_APP");
        if (osxOfficialApp == null || osxOfficialApp.isEmpty() || osxOfficialApp.equalsIgnoreCase("false")) {
            logger.warn("WARN: macOS app NOT launched from official launcher!");
        }
    }

    private static void checkMemoryRequirements() {
        final var maxMem = Runtime.getRuntime().maxMemory();
        // more than 450MB avail...
        if (maxMem < 450 * FileUtils.ONE_MB) {
            if (SystemUtils.isJavaAwtHeadless()) {
                System.err.println("Die VM hat nicht genügend Arbeitsspeicher zugewiesen bekommen.");
                System.err.println("Nutzen Sie den Startparameter -Xmx512M für Minimumspeicher");
            } else {
                JavaFxUtils.invokeInFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(Konstanten.PROGRAMMNAME);
                    alert.setHeaderText("Speicherwarnung");
                    alert.setContentText("MediathekView hat nicht genügend Arbeitsspeicher zugewiesen bekommen.\n" +
                            "Es werden mindestens 512MB RAM benötigt.");
                    alert.showAndWait();
                });
            }

            System.exit(3);
        }
    }

    private static void startGuiMode() {
        SwingUtilities.invokeLater(() ->
        {
            splashScreen.ifPresent(s -> s.update(UIProgressState.INIT_FX));

            splashScreen.ifPresent(s -> s.update(UIProgressState.FILE_CLEANUP));
            if (SystemUtils.IS_OS_MAC_OSX) {
                checkForOfficialOSXAppUse();
                System.setProperty(MAC_SYSTEM_PROPERTY_APPLE_LAF_USE_SCREEN_MENU_BAR, Boolean.TRUE.toString());
                cleanupOsxFiles();
            }

            if (Config.isDebugModeEnabled()) {
                // use for debugging EDT violations
                RepaintManager.setCurrentManager(new ThreadCheckingRepaintManager());
                logger.info("Swing Thread checking repaint manager installed.");
            }

            splashScreen.ifPresent(s -> s.update(UIProgressState.START_UI));
            var window = getPlatformWindow();
            splashScreen.ifPresent(SplashScreen::close);
            window.setVisible(true);
            /*
                on windows there is a strange behaviour that the main window gets sent behind
                other open windows after the splash screen is closed.
             */
            if (SystemUtils.IS_OS_WINDOWS) {
                window.toFront();
                window.requestFocus();
            }
        });
    }

    private static MediathekGui getPlatformWindow() {
        MediathekGui window;
        Stopwatch watch = Stopwatch.createStarted();

        if (SystemUtils.IS_OS_MAC_OSX) {
            window = new MediathekGuiMac();
        } else if (SystemUtils.IS_OS_WINDOWS) {
            window = new MediathekGuiWindows();
        } else if (SystemUtils.IS_OS_UNIX) {
            window = new MediathekGuiX11();
        } else
            throw new IllegalStateException("Unknown operating system detected! Cannot create main window");

        watch.stop();
        logger.trace("getPlatformWindow(): {}", watch);

        return window;
    }
}
