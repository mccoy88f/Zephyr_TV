package link.mccoy88f.zephyr
internal object NotificationFeedPolicy {
 fun accept(pkg:String?,category:String?,creator:String?)=ExternalResume.allowed(pkg) && category=="recommendation" && creator==pkg
}
