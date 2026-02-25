package avinash.app.headlinr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import avinash.app.headlinr.ui.navigation.AppNavigation
import avinash.app.headlinr.ui.theme.HeadlinrTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeadlinrTheme {
                AppNavigation()
            }
        }
    }
}
