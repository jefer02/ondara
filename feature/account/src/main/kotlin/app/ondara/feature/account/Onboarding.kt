package app.ondara.feature.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import app.ondara.core.domain.AccountRepository
import app.ondara.core.domain.SettingsRepository
import app.ondara.core.designsystem.component.*
import app.ondara.core.designsystem.theme.*
import app.ondara.core.model.AccountsState
import app.ondara.core.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val name: String = "",
    val accounts: AccountsState = AccountsState(),
    val themes: List<ThemeSpec> = emptyList(),
    val selectedTheme: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)
sealed interface OnboardingEvent {
    data class Name(val name: String) : OnboardingEvent
    data class Theme(val id: String) : OnboardingEvent
    data class Navigate(val nav: Nav) : OnboardingEvent
    data object Next : OnboardingEvent
    data object Back : OnboardingEvent
    data object Finish : OnboardingEvent
}
sealed interface OnboardingEffect {
    data object Finished : OnboardingEffect
    data class Navigate(val nav: Nav) : OnboardingEffect
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    accounts: AccountRepository,
    themes: ThemeStore,
) : MviViewModel<OnboardingUiState, OnboardingEvent, OnboardingEffect>(OnboardingUiState()) {
    init {
        viewModelScope.launch {
            val preferences = settings.settings.first()
            setState { copy(name = preferences.profileName, selectedTheme = preferences.themeId) }
        }
        viewModelScope.launch { accounts.accounts.collect { setState { copy(accounts = it) } } }
        viewModelScope.launch { themes.themes.collect { setState { copy(themes = it) } } }
    }
    override fun onEvent(event: OnboardingEvent) {
        when (event) {
            is OnboardingEvent.Name -> setState { copy(name = event.name) }
            is OnboardingEvent.Navigate -> emit(OnboardingEffect.Navigate(event.nav))
            OnboardingEvent.Next -> setState { copy(step = (step + 1).coerceAtMost(3)) }
            OnboardingEvent.Back -> setState { copy(step = (step - 1).coerceAtLeast(0)) }
            is OnboardingEvent.Theme -> save {
                settings.update { it.copy(themeId = event.id) }
                setState { copy(selectedTheme = event.id) }
            }
            OnboardingEvent.Finish -> if (!state.value.saving) save {
                settings.update {
                    it.copy(
                        profileName = state.value.name.trim(), onboardingDone = true,
                        joinedAtMs = it.joinedAtMs.takeIf { time -> time > 0 } ?: System.currentTimeMillis(),
                    )
                }
                emit(OnboardingEffect.Finished)
            }
        }
    }
    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            setState { copy(saving = true, error = null) }
            try { action() } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setState { copy(error = error.message ?: "No se pudieron guardar los cambios") }
            } finally { setState { copy(saving = false) } }
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onNavigate: (Nav) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val event = viewModel::onEvent
    val motion = OndaraTheme.motion
    val colors = OndaraTheme.colors
    CollectEffects(viewModel.effects) { effect ->
        when (effect) { OnboardingEffect.Finished -> onFinished(); is OnboardingEffect.Navigate -> onNavigate(effect.nav) }
    }
    Box(modifier.fillMaxSize().background(colors.background)) {
        if (state.step == 0) MeshGradientBackground(ArtworkPalette(colors.accent, colors.blockPrimary, colors.blockMuted, colors.blockLight), Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(OndaraTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xl),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.step > 0) OndaraButton("Atrás", { event(OnboardingEvent.Back) }, style = ButtonStyle.GHOST)
                OndaraButton("Omitir", { event(OnboardingEvent.Finish) }, style = ButtonStyle.GHOST, enabled = !state.saving)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.sm)) {
                repeat(4) { index ->
                    Box(Modifier.weight(1f).height(OndaraTheme.spacing.xs).clip(OndaraTheme.shapes.control)
                        .background(if (state.step == index) colors.accent else colors.outline))
                }
            }
            AnimatedContent(
                state.step, modifier = Modifier.weight(1f), label = "Primer arranque",
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    slideInHorizontally(motion.spring()) { if (motion.reduced) 0 else it * direction } togetherWith
                        slideOutHorizontally(motion.spring()) { if (motion.reduced) 0 else -it * direction }
                },
            ) { step ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.xl)) {
                    when (step) {
                        0 -> Text("Ondara · Tu música, en otra frecuencia.", style = OndaraTheme.type.hero)
                        1 -> {
                            Text("¿Cómo te llamas?", style = OndaraTheme.type.display)
                            OndaraTextField(state.name, { event(OnboardingEvent.Name(it)) }, placeholder = "Nombre del perfil (opcional)")
                        }
                        2 -> {
                            Text("Tu música, conectada", style = OndaraTheme.type.display)
                            Text("Conectar cuentas es opcional. Puedes hacerlo más tarde.")
                            Text(state.accounts.activeYouTube?.let { "YouTube Music · ${it.name}" } ?: "YouTube Music · Sin conectar")
                            OndaraButton("Conectar YouTube Music", { event(OnboardingEvent.Navigate(Nav.Login)) })
                            Text(state.accounts.spotify?.let { "Spotify · ${it.name}" } ?: "Spotify · Sin conectar")
                            OndaraButton("Conectar Spotify", { event(OnboardingEvent.Navigate(Nav.SpotifyConnect)) })
                        }
                        3 -> {
                            Text("Elige tu frecuencia", style = OndaraTheme.type.display)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md)) {
                                items(state.themes, key = { it.id }, contentType = { "tema" }) { theme ->
                                    OndaraTheme(theme) {
                                        Column(
                                            Modifier.width(OndaraTheme.spacing.touch * 4).clip(OndaraTheme.shapes.large)
                                                .background(OndaraTheme.colors.surface).padding(OndaraTheme.spacing.lg),
                                            verticalArrangement = Arrangement.spacedBy(OndaraTheme.spacing.md),
                                        ) {
                                            Text(theme.name, style = OndaraTheme.type.title)
                                            Row {
                                                listOf(OndaraTheme.colors.blockPrimary, OndaraTheme.colors.blockLight, OndaraTheme.colors.blockMuted).forEach {
                                                    Box(Modifier.weight(1f).height(OndaraTheme.spacing.touch).background(it))
                                                }
                                            }
                                            OndaraButton(if (theme.id == state.selectedTheme) "Seleccionado" else "Elegir", { event(OnboardingEvent.Theme(theme.id)) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ErrorMessage(state.error)
            OndaraButton(
                if (state.step == 3) "Empezar" else "Continuar",
                { event(if (state.step == 3) OnboardingEvent.Finish else OnboardingEvent.Next) },
                Modifier.fillMaxWidth(), enabled = !state.saving,
            )
            if (state.step == 3) AuthorFooter("Hecho con ♥ por ${OndaraAuthor.NAME}", onClick = null)
        }
    }
}
