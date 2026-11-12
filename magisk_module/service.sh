#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
PKG="com.blackout.focus"
ACC="$PKG/com.blackout.focus.service.BlackoutAccessibilityService"
NOTIF="$PKG/com.blackout.focus.service.BlackoutNotificationListenerService"
ADMIN="$PKG/com.blackout.focus.receiver.BlackoutDeviceAdminReceiver"

appops set $PKG SYSTEM_ALERT_WINDOW allow
appops set $PKG SCHEDULE_EXACT_ALARM allow
appops set $PKG RUN_ANY_IN_BACKGROUND allow
dumpsys deviceidle whitelist +$PKG

curr_acc=$(settings get secure enabled_accessibility_services)
if [ "$curr_acc" = "null" ] || [ -z "$curr_acc" ]; then
    settings put secure enabled_accessibility_services "$ACC"
elif ! echo "$curr_acc" | grep -q "$ACC"; then
    settings put secure enabled_accessibility_services "$curr_acc:$ACC"
fi
settings put secure accessibility_enabled 1

curr_notif=$(settings get secure enabled_notification_listeners)
if [ "$curr_notif" = "null" ] || [ -z "$curr_notif" ]; then
    settings put secure enabled_notification_listeners "$NOTIF"
elif ! echo "$curr_notif" | grep -q "$NOTIF"; then
    settings put secure enabled_notification_listeners "$curr_notif:$NOTIF"
fi

dpm set-active-admin $ADMIN
dpm set-device-owner $ADMIN
am start-foreground-service -n $PKG/com.blackout.focus.service.BlackoutForegroundService -a com.blackout.focus.action.START_OR_RESUME
