// The bridge between TableGrip (Kotlin, through JNA) and Sparkle. Built into Contents/Frameworks/libTableGripUpdater.dylib
// next to Sparkle.framework by app/build.gradle.kts. Everything Sparkle touches runs on the main thread, which is
// AppKit's (AWT runs NSApplication there). Only dg_updater_start waits for it, and must not be called on the AWT
// event thread: AppKit sometimes waits for that thread, and the two would deadlock.

#import <stdatomic.h>

#import <AppKit/AppKit.h>
#import <Sparkle/Sparkle.h>

@interface DGUpdaterDelegate : NSObject <SPUUpdaterDelegate>
@property(atomic, copy) NSString *feed;
@property(atomic) BOOL beta;
@end

static _Atomic double lastCheck;

// What the last check found, for Settings → Updates. Written on the main thread, read from the app's; guarded by the
// delegate. The codes are Kotlin's UpdateStatus.
enum { DGIdle, DGChecking, DGUpToDate, DGAvailable, DGReady, DGFailed };
static int state = DGIdle;
static NSString *detail;  // The version found, or why the check failed.
static void (^installNow)(void);

static void setState(int newState, NSString *newDetail) {
    @synchronized([DGUpdaterDelegate class]) {
        // A downloaded update stays ready to install whatever later checks say.
        if (state == DGReady && newState != DGReady) return;
        state = newState;
        detail = [newDetail copy];
    }
}

@implementation DGUpdaterDelegate
- (nullable NSString *)feedURLStringForUpdater:(SPUUpdater *)updater {
    return self.feed;
}

- (NSSet<NSString *> *)allowedChannelsForUpdater:(SPUUpdater *)updater {
    return self.beta ? [NSSet setWithObject:@"beta"] : [NSSet set];
}

- (void)updater:(SPUUpdater *)updater didFindValidUpdate:(SUAppcastItem *)item {
    setState(DGAvailable, item.displayVersionString);
}

- (void)updaterDidNotFindUpdate:(SPUUpdater *)updater error:(NSError *)error {
    setState(DGUpToDate, nil);
}

- (void)updater:(SPUUpdater *)updater didAbortWithError:(NSError *)error {
    if (!([error.domain isEqualToString:SUSparkleErrorDomain] && error.code == SUNoUpdateError)) setState(DGFailed, error.localizedDescription);
}

// The update was downloaded (automatic downloads) and installs when the app quits. Keeping the handler lets the user
// install it now from Settings instead; Sparkle still installs it on quit either way.
- (BOOL)updater:(SPUUpdater *)updater willInstallUpdateOnQuit:(SUAppcastItem *)item immediateInstallationBlock:(void (^)(void))immediateInstallHandler {
    installNow = [immediateInstallHandler copy];
    setState(DGReady, item.displayVersionString);
    return YES;
}

- (void)updater:(SPUUpdater *)updater didFinishUpdateCycleForUpdateCheck:(SPUUpdateCheck)updateCheck error:(nullable NSError *)error {
    NSDate *date = updater.lastUpdateCheckDate;
    if (date != nil) atomic_store(&lastCheck, date.timeIntervalSince1970);
}
@end

static SPUStandardUpdaterController *controller;
static DGUpdaterDelegate *delegate;

static void onMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

static void apply(int automaticChecks, int automaticDownloads) {
    SPUUpdater *updater = controller.updater;
    updater.automaticallyChecksForUpdates = automaticChecks != 0;
    updater.automaticallyDownloadsUpdates = automaticDownloads != 0;
}

/** Starts the updater once. Returns 1 when it runs, 0 when Sparkle refused (e.g. no SUPublicEDKey in Info.plist). */
int dg_updater_start(const char *feed, int automaticChecks, int automaticDownloads, int beta) {
    NSString *feedUrl = [NSString stringWithUTF8String:feed];
    __block int started = 0;
    onMain(^{
        if (controller == nil) {
            delegate = [DGUpdaterDelegate new];
            delegate.feed = feedUrl;
            delegate.beta = beta != 0;
            controller = [[SPUStandardUpdaterController alloc] initWithStartingUpdater:NO updaterDelegate:delegate userDriverDelegate:nil];
            NSError *error = nil;
            if (![controller.updater startUpdater:&error]) {
                NSLog(@"TableGrip updater didn't start: %@", error);
                controller = nil;
                return;
            }
        }
        apply(automaticChecks, automaticDownloads);
        NSDate *date = controller.updater.lastUpdateCheckDate;
        if (date != nil) atomic_store(&lastCheck, date.timeIntervalSince1970);
        started = 1;
    });
    return started;
}

/** Applies changed settings. A changed channel or feed takes effect from the next check. */
void dg_updater_configure(const char *feed, int automaticChecks, int automaticDownloads, int beta) {
    NSString *feedUrl = [NSString stringWithUTF8String:feed];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (controller == nil) return;
        delegate.feed = feedUrl;
        delegate.beta = beta != 0;
        [controller.updater resetUpdateCycleAfterShortDelay];
        apply(automaticChecks, automaticDownloads);
    });
}

/** "Check for Updates…": shows Sparkle's window, also to say there's nothing new. */
void dg_updater_check(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [controller checkForUpdates:nil];
    });
}

/** Checks without any window; the result comes through dg_updater_status. */
void dg_updater_probe(void) {
    setState(DGChecking, nil);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (controller.updater.sessionInProgress) setState(DGIdle, nil);  // A check is already showing its window.
        else [controller.updater checkForUpdateInformation];
    });
}

/** The status code, with the version or error copied into buffer (UTF-8, cut to size). */
int dg_updater_status(char *buffer, int size) {
    @synchronized([DGUpdaterDelegate class]) {
        if (size > 0) {
            buffer[0] = 0;
            if (detail != nil && ![detail getCString:buffer maxLength:size encoding:NSUTF8StringEncoding]) buffer[0] = 0;
        }
        return state;
    }
}

/**
 * Installs the downloaded update and relaunches. Returns 0 if there's none ready. The app must already have agreed to
 * quit: Sparkle terminates it, and the quit handler has to let that through.
 */
int dg_updater_install_now(void) {
    void (^handler)(void) = installNow;
    if (handler == nil) return 0;
    dispatch_async(dispatch_get_main_queue(), handler);
    return 1;
}

/** Seconds since 1970 of the last check, or 0 if there hasn't been one. */
double dg_updater_last_check(void) {
    return atomic_load(&lastCheck);
}

/**
 * Quits through AppKit, so Sparkle sees the app terminate and installs a downloaded update. The app's quit handler
 * must let this one through: the user already agreed to quit.
 */
void dg_updater_terminate(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        [NSApp terminate:nil];
    });
}
