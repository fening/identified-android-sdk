package cc.identified.sdk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.identified.sdk.*
import cc.identified.sdk.capture.*
import cc.identified.sdk.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentifiedContent(
    configuration: IdentifiedConfiguration,
    onFinish: (IdentifiedResult) -> Unit,
) {
    val factory =
        remember(configuration) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FlowViewModel(
                        ClientFlowApi(ApiClient(configuration.baseUrl, configuration.sessionToken))
                    )
                        as T
            }
        }
    val model: FlowViewModel = viewModel(factory = factory)
    val state by model.state.collectAsState()
    val callback by rememberUpdatedState(onFinish)
    val context = LocalContext.current
    val copy = remember(context) { Copy(context) }
    LaunchedEffect(model) { model.loadIfNeeded() }
    LaunchedEffect(state.result) { model.takeResult()?.let(callback) }
    IdentifiedTheme(configuration.appearance) {
        CompositionLocalProvider(LocalCopy provides copy) {
            BackHandler { model.back() }
            Surface(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (state.stage) {
                    Stage.LOADING -> Shell(model, state) { Loading(copy.text("entry.checking")) }
                    Stage.UNAVAILABLE ->
                        Shell(
                            model,
                            state,
                            "entry.unavailable.title",
                            "entry.unavailable.body",
                            footer = {
                                Primary(
                                    copy.text("entry.unavailable.retry"),
                                    !state.busy,
                                    "identified-continue",
                                    model::load,
                                )
                            },
                        ) {}
                    Stage.READY ->
                        key(state.route) {
                            when (val route = state.route) {
                                Route.Entry -> EntryScreen(model, state)
                                Route.Consent -> ConsentScreen(model, state)
                                Route.Terms -> {
                                    ConsentScreen(model, state)
                                    ModalBottomSheet(
                                        onDismissRequest = model::back,
                                        sheetState =
                                            rememberModalBottomSheetState(
                                                skipPartiallyExpanded = true
                                            ),
                                    ) {
                                        TermsScreen(model, state)
                                    }
                                }
                                is Route.Step ->
                                    when (val d = route.destination) {
                                        Destination.Checklist -> ChecklistScreen(model, state)
                                        Destination.YourName -> NameScreen(model, state)
                                        Destination.IdDetails -> IdScreen(model, state)
                                        is Destination.Documents ->
                                            CaptureScreen(model, state, d.startAt, false)
                                        Destination.Selfie ->
                                            CaptureScreen(model, state, null, true)
                                        Destination.ProofOfAddress -> AddressScreen(model, state)
                                        Destination.Review -> ReviewScreen(model, state)
                                        Destination.Status -> StatusScreen(model, state)
                                    }
                            }
                        }
                }
            }
        }
    }
}
