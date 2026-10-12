import SwiftUI
import FirebaseCore
import FirebaseMessaging
import UserNotifications

final class AppDelegate:NSObject,UIApplicationDelegate,UNUserNotificationCenterDelegate,MessagingDelegate {
    func application(_ application:UIApplication,didFinishLaunchingWithOptions options:[UIApplication.LaunchOptionsKey:Any]?=nil)->Bool {
        FirebaseApp.configure()
        Messaging.messaging().delegate=self
        UNUserNotificationCenter.current().delegate=self
        UNUserNotificationCenter.current().requestAuthorization(options:[.alert,.sound,.badge]){_,_ in DispatchQueue.main.async{application.registerForRemoteNotifications()}}
        return true
    }
    func application(_ application:UIApplication,didRegisterForRemoteNotificationsWithDeviceToken token:Data) {Messaging.messaging().apnsToken=token}
    func messaging(_ messaging:Messaging,didReceiveRegistrationToken token:String?) {
        guard let token else{return};Keychain.set("fcm-token",token)
        Task {@MainActor in await AppModel.shared.sync()}
    }
    func application(_ application:UIApplication,didReceiveRemoteNotification info:[AnyHashable:Any],fetchCompletionHandler complete:@escaping(UIBackgroundFetchResult)->Void) {
        Task {@MainActor in await AppModel.shared.sync();complete(.newData)}
    }
    func userNotificationCenter(_ center:UNUserNotificationCenter,willPresent notification:UNNotification,withCompletionHandler complete:@escaping(UNNotificationPresentationOptions)->Void) {complete([])}
}

@main struct SchoolHelpApp:App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) var phase
    @StateObject private var model=AppModel.shared
    var body:some Scene {
        WindowGroup {
            ContentView(model:model)
                .onChange(of:phase) {_,new in
                    if new == .active {Task {await model.sync()}}
                    if new == .background {model.savePlaybackPosition()}
                }
        }
    }
}
