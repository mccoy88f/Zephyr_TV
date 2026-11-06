package link.mccoy88f.zephyr.torrent

import android.app.Activity
import android.os.Bundle

/**
 * The engine started by Zephyr TV when its provider doesn't answer: some TVs don't let one app start another's
 * process for a provider (the makers' "autostart" rules, or an app never opened). Shows nothing and closes at once;
 * the session joins the network meanwhile, and Zephyr TV asks the provider again. All it can be made to do is start.
 */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.use(this)
        Engine.warm()
        finish()
    }
}
