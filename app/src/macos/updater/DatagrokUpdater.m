// The bridge between Datagrok (Kotlin, through JNA) and Sparkle. Built into Contents/Frameworks/libDatagrokUpdater.dylib
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

@implementation DGUpdaterDelegate
- (nullable NSString *)feedURLStringForUpdater:(SPUUpdater *)updater {
    return self.feed;
}

- (NSSet<NSString *> *)allowedChannelsForUpdater:(SPUUpdater *)updater {
    return self.beta ? [NSSet setWithObject:@"beta"] : [NSSet set];
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
                NSLog(@"Datagrok updater didn't start: %@", error);
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
