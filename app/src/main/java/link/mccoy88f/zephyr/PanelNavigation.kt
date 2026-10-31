package link.mccoy88f.zephyr
internal object PanelNavigation {
 enum class Action { FOCUS, PAGE, DEFAULT }
 fun route(explicitTarget:Boolean,hasPageSides:Boolean)=when {explicitTarget->Action.FOCUS;hasPageSides->Action.PAGE;else->Action.DEFAULT}
}
