package chat.simplex.common.views.chatlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextRange
import dev.icerock.moko.resources.compose.painterResource
import dev.icerock.moko.resources.compose.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import chat.simplex.common.SettingsViewState
import chat.simplex.common.model.*
import chat.simplex.common.model.ChatController.stopRemoteHostAndReloadHosts
import chat.simplex.common.ui.theme.*
import chat.simplex.common.views.helpers.*
import chat.simplex.common.views.onboarding.WhatsNewView
import chat.simplex.common.views.onboarding.shouldShowWhatsNew
import chat.simplex.common.views.usersettings.SettingsView
import chat.simplex.common.platform.*
import chat.simplex.common.views.call.Call
import chat.simplex.common.views.newchat.*
import chat.simplex.res.MR
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun ChatListView(chatModel: ChatModel, settingsState: SettingsViewState, setPerformLA: (Boolean) -> Unit, stopped: Boolean) {
  val newChatSheetState by rememberSaveable(stateSaver = AnimatedViewState.saver()) { mutableStateOf(MutableStateFlow(AnimatedViewState.GONE)) }
  val showNewChatSheet = {
    newChatSheetState.value = AnimatedViewState.VISIBLE
  }
  val hideNewChatSheet: (animated: Boolean) -> Unit = { animated ->
    if (animated) newChatSheetState.value = AnimatedViewState.HIDING
    else newChatSheetState.value = AnimatedViewState.GONE
  }
  val oneHandUI = remember { chatModel.controller.appPrefs.oneHandUI }

  LaunchedEffect(Unit) {
    if (shouldShowWhatsNew(chatModel)) {
      delay(1000L)
      ModalManager.center.showCustomModal { close -> WhatsNewView(close = close) }
    }
  }
  LaunchedEffect(chatModel.clearOverlays.value) {
    if (chatModel.clearOverlays.value && newChatSheetState.value.isVisible()) hideNewChatSheet(false)
  }
  if (appPlatform.isDesktop) {
    KeyChangeEffect(chatModel.chatId.value) {
      if (chatModel.chatId.value != null) {
        ModalManager.end.closeModalsExceptFirst()
      }
      AudioPlayer.stop()
      VideoPlayerHolder.stopAll()
    }
  }
  val endPadding = if (appPlatform.isDesktop) 56.dp else 0.dp
  val searchText = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
  val scope = rememberCoroutineScope()
  val (userPickerState, scaffoldState ) = settingsState
  Scaffold(topBar = { Box(Modifier.padding(end = endPadding)) { ChatListTopBar(stopped) } },
    bottomBar = { Box(Modifier.padding(end = endPadding)) { ChatListBottomToolbar(scaffoldState.drawerState, userPickerState) } },
    scaffoldState = scaffoldState,
    drawerContent = {
      tryOrShowError("Settings", error = { ErrorSettingsView() }) {
        SettingsView(chatModel, setPerformLA, scaffoldState.drawerState)
      }
    },
    contentColor = LocalContentColor.current,
    drawerContentColor = LocalContentColor.current,
    drawerScrimColor = MaterialTheme.colors.onSurface.copy(alpha = if (isInDarkTheme()) 0.16f else 0.32f),
    drawerGesturesEnabled = appPlatform.isAndroid,
    floatingActionButton = {
      if (searchText.value.text.isEmpty() && !chatModel.desktopNoUserNoRemote && chatModel.chatRunning.value == true) {
        var bottom = DEFAULT_PADDING
        if (oneHandUI.state.value) {
          bottom = DEFAULT_BOTTOM_PADDING
        } else {
          bottom -= 16.dp
        }

        FloatingActionButton(
          onClick = {
            if (!stopped) {
              if (newChatSheetState.value.isVisible()) hideNewChatSheet(true) else showNewChatSheet()
            }
          },
          Modifier.padding(end = DEFAULT_PADDING - 16.dp + endPadding, bottom = bottom).size(AppBarHeight * fontSizeSqrtMultiplier),
          elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp,
          ),
          backgroundColor = if (!stopped) MaterialTheme.colors.primary else MaterialTheme.colors.secondary,
          contentColor = Color.White
        ) {
          Icon(if (!newChatSheetState.collectAsState().value.isVisible()) painterResource(MR.images.ic_edit_filled) else painterResource(MR.images.ic_close), stringResource(MR.strings.add_contact_or_create_group), Modifier.size(24.dp * fontSizeSqrtMultiplier))
        }
      }
    }
  ) {
    var modifier = Modifier.padding(it).padding(end = endPadding)
    if (oneHandUI.state.value) {
      modifier = modifier.scale(scaleX = 1f, scaleY = -1f)
    }

    Box(modifier) {
      Box(
        modifier = Modifier
          .fillMaxSize()
      ) {
        if (!chatModel.desktopNoUserNoRemote) {
          ChatList(chatModel, searchText = searchText, oneHandUI = oneHandUI)
        }
        if (chatModel.chats.isEmpty() && !chatModel.switchingUsersAndHosts.value && !chatModel.desktopNoUserNoRemote) {
          Text(stringResource(
            if (chatModel.chatRunning.value == null) MR.strings.loading_chats else MR.strings.you_have_no_chats), Modifier.align(Alignment.Center), color = MaterialTheme.colors.secondary)
          if (!stopped && !newChatSheetState.collectAsState().value.isVisible() && chatModel.chatRunning.value == true && searchText.value.text.isEmpty()) {
            OnboardingButtons(showNewChatSheet)
          }
        }
      }
    }
  }
  if (searchText.value.text.isEmpty()) {
    if (appPlatform.isDesktop) {
      val call = remember { chatModel.activeCall }.value
      if (call != null) {
        ActiveCallInteractiveArea(call, newChatSheetState)
      }
    }
    // TODO disable this button and sheet for the duration of the switch
    tryOrShowError("NewChatSheet", error = {}) {
      NewChatSheet(chatModel, newChatSheetState, stopped, hideNewChatSheet)
    }
  }
  if (appPlatform.isAndroid) {
    tryOrShowError("UserPicker", error = {}) {
      UserPicker(chatModel, userPickerState) {
        scope.launch { if (scaffoldState.drawerState.isOpen) scaffoldState.drawerState.close() else scaffoldState.drawerState.open() }
        userPickerState.value = AnimatedViewState.GONE
      }
    }
  }
}

@Composable
private fun OnboardingButtons(openNewChatSheet: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(DEFAULT_PADDING), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Bottom) {
    ConnectButton(generalGetString(MR.strings.tap_to_start_new_chat), openNewChatSheet)
    val color = MaterialTheme.colors.primaryVariant
    Canvas(modifier = Modifier.width(40.dp).height(10.dp), onDraw = {
      val trianglePath = Path().apply {
        moveTo(0.dp.toPx(), 0f)
        lineTo(16.dp.toPx(), 0.dp.toPx())
        lineTo(8.dp.toPx(), 10.dp.toPx())
        lineTo(0.dp.toPx(), 0.dp.toPx())
      }
      drawPath(
        color = color,
        path = trianglePath
      )
    })
    Spacer(Modifier.height(62.dp))
  }
}

@Composable
private fun ConnectButton(text: String, onClick: () -> Unit) {
  Button(
    onClick,
    shape = RoundedCornerShape(21.dp),
    colors = ButtonDefaults.textButtonColors(
      backgroundColor = MaterialTheme.colors.primaryVariant
    ),
    elevation = null,
    contentPadding = PaddingValues(horizontal = DEFAULT_PADDING, vertical = DEFAULT_PADDING_HALF),
    modifier = Modifier.height(42.dp)
  ) {
    Text(text, color = Color.White)
  }
}

@Composable
private fun ChatListTopBar(stopped: Boolean) {
  val serversSummary: MutableState<PresentedServersSummary?> = remember { mutableStateOf(null) }

  val barButtons = arrayListOf<@Composable RowScope.() -> Unit>()
  if (stopped) {
    barButtons.add {
      IconButton(onClick = {
        AlertManager.shared.showAlertMsg(
          generalGetString(MR.strings.chat_is_stopped_indication),
          generalGetString(MR.strings.you_can_start_chat_via_setting_or_by_restarting_the_app)
        )
      }) {
        Icon(
          painterResource(MR.images.ic_report_filled),
          generalGetString(MR.strings.chat_is_stopped_indication),
          tint = Color.Red,
        )
      }
    }
  }

  val clipboard = LocalClipboardManager.current

  DefaultTopAppBar(
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DEFAULT_SPACE_AFTER_ICON)) {
        Text(
          stringResource(MR.strings.your_chats),
          color = MaterialTheme.colors.onBackground,
          fontWeight = FontWeight.SemiBold,
        )
        SubscriptionStatusIndicator(
          serversSummary = serversSummary,
          click = {
            ModalManager.start.closeModals()
            ModalManager.start.showModalCloseable(
              endButtons = {
                val summary = serversSummary.value
                if (summary != null) {
                  ShareButton {
                    val json = Json {
                      prettyPrint = true
                    }

                    val text = json.encodeToString(PresentedServersSummary.serializer(), summary)
                    clipboard.shareText(text)
                  }
                }
              }
            ) { ServersSummaryView(chatModel.currentRemoteHost.value, serversSummary) }
          }
        )
      }
    },
    onTitleClick = null,
    showSearch = false,
    onSearchValueChanged = {},
    buttons = barButtons
  )
  Divider(Modifier.padding(top = AppBarHeight))
}

@Composable
fun SettingsButton(drawerState: DrawerState, userPickerState: MutableStateFlow<AnimatedViewState>) {
  val scope = rememberCoroutineScope()
  if (chatModel.users.isEmpty() && !chatModel.desktopNoUserNoRemote) {
    NavigationButtonMenu { scope.launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() } }
  } else {
    val users by remember { derivedStateOf { chatModel.users.filter { u -> u.user.activeUser || !u.user.hidden } } }
    val allRead = users
      .filter { u -> !u.user.activeUser && !u.user.hidden }
      .all { u -> u.unreadCount == 0 }

    UserProfileButton(chatModel.currentUser.value?.profile?.image, allRead) {
      if (users.size == 1 && chatModel.remoteHosts.isEmpty()) {
        scope.launch { drawerState.open() }
      } else {
        userPickerState.value = AnimatedViewState.VISIBLE
      }
    }
  }
}

@Composable
fun toolbarIcon(icon: Painter) {
  Icon(
    icon,
    contentDescription = null,
    Modifier.size(24.dp * fontSizeSqrtMultiplier),
    tint = MaterialTheme.colors.secondary
  )
}

@Composable
fun ChatListToolbarButton(icon: @Composable () -> Unit, title: String, onClick: () -> Unit) {
  Surface(
    Modifier
      .size(56.dp * fontSizeSqrtMultiplier),
    shape = RoundedCornerShape(10.dp),
    color = Color.Transparent,
  ) {
    Column(
      Modifier
        .clickable { onClick () },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      icon()
      Text(
        title,
        style = MaterialTheme.typography.subtitle2.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp * fontSizeSqrtMultiplier),
        color = MaterialTheme.colors.secondary
      )
    }
  }
}

@Composable
private fun ChatListBottomToolbar(drawerState: DrawerState, userPickerState: MutableStateFlow<AnimatedViewState>) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(BottomAppBarHeight)
      .background(MaterialTheme.colors.background.mixWith(MaterialTheme.colors.onBackground, 0.97f))
  ) {
    Divider()
    Row(
      Modifier
        .fillMaxHeight()
        .fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SettingsButton(drawerState, userPickerState)

      ChatListToolbarButton(
        icon = { toolbarIcon(painterResource(MR.images.ic_chat_bubble_filled)) },
        title = generalGetString(MR.strings.your_chats),
        onClick = {  }
      )
    }
  }
}

@Composable
fun SubscriptionStatusIndicator(serversSummary: MutableState<PresentedServersSummary?>, click: (() -> Unit)) {
  var subs by remember { mutableStateOf(SMPServerSubs.newSMPServerSubs) }
  var sess by remember { mutableStateOf(ServerSessions.newServerSessions) }
  var timer: Job? by remember { mutableStateOf(null) }

  val fetchInterval: Duration = 1.seconds

  val scope = rememberCoroutineScope()

  fun setServersSummary() {
    withBGApi {
      serversSummary.value = chatModel.controller.getAgentServersSummary(chatModel.remoteHostId())

      serversSummary.value?.let {
        subs = it.allUsersSMP.smpTotals.subs
        sess = it.allUsersSMP.smpTotals.sessions
      }
    }
  }

  LaunchedEffect(Unit) {
    setServersSummary()
    timer = timer ?: scope.launch {
      while (true) {
        delay(fetchInterval.inWholeMilliseconds)
        setServersSummary()
      }
    }
  }

  fun stopTimer() {
    timer?.cancel()
    timer = null
  }

  DisposableEffect(Unit) {
    onDispose {
      stopTimer()
    }
  }

  SimpleButtonFrame(click = click) {
    SubscriptionStatusIndicatorView(subs = subs, sess = sess)
  }
}

@Composable
fun UserProfileButton(image: String?, allRead: Boolean, onButtonClicked: () -> Unit) {
  ChatListToolbarButton(
    icon = {
      Box {
        ProfileImage(
          image = image,
          size = 24.dp * fontSizeSqrtMultiplier,
          color = MaterialTheme.colors.secondaryVariant.mixWith(
            MaterialTheme.colors.onBackground,
            0.97f
          )
        )
        if (!allRead) {
          unreadBadge()
        }
      }
    },
    onClick = onButtonClicked,
    title = generalGetString(MR.strings.toolbar_settings),

    )

  if (appPlatform.isDesktop) {
    val h by remember { chatModel.currentRemoteHost }
    if (h != null) {
      Spacer(Modifier.width(12.dp))
      HostDisconnectButton {
        stopRemoteHostAndReloadHosts(h!!, true)
      }
    }
  }
}

@Composable
private fun BoxScope.unreadBadge(text: String? = "") {
  Text(
    text ?: "",
    color = MaterialTheme.colors.onPrimary,
    fontSize = 6.sp,
    modifier = Modifier
      .background(MaterialTheme.colors.primary, shape = CircleShape)
      .badgeLayout()
      .padding(horizontal = 3.dp)
      .padding(vertical = 1.dp)
      .align(Alignment.TopEnd)
  )
}

@Composable
private fun ToggleFilterEnabledButton() {
  val pref = remember { ChatController.appPrefs.showUnreadAndFavorites }
  IconButton(onClick = { pref.set(!pref.get()) }) {
    val sp16 = with(LocalDensity.current) { 16.sp.toDp() }
    Icon(
      painterResource(MR.images.ic_filter_list),
      null,
      tint = if (pref.state.value) MaterialTheme.colors.background else MaterialTheme.colors.secondary,
      modifier = Modifier
        .padding(3.dp)
        .background(color = if (pref.state.value) MaterialTheme.colors.primary else Color.Unspecified, shape = RoundedCornerShape(50))
        .border(width = 1.dp, color = if (pref.state.value) MaterialTheme.colors.primary else Color.Unspecified, shape = RoundedCornerShape(50))
        .padding(3.dp)
        .size(sp16)
    )
  }
}

@Composable
expect fun ActiveCallInteractiveArea(call: Call, newChatSheetState: MutableStateFlow<AnimatedViewState>)

fun connectIfOpenedViaUri(rhId: Long?, uri: URI, chatModel: ChatModel) {
  Log.d(TAG, "connectIfOpenedViaUri: opened via link")
  if (chatModel.currentUser.value == null) {
    chatModel.appOpenUrl.value = rhId to uri
  } else {
    withBGApi {
      planAndConnect(rhId, uri, incognito = null, close = null)
    }
  }
}

@Composable
private fun ChatListSearchBar(listState: LazyListState, searchText: MutableState<TextFieldValue>, searchShowingSimplexLink: MutableState<Boolean>, searchChatFilteredBySimplexLink: MutableState<String?>, oneHandUI: SharedPreference<Boolean>) {
  var modifier = Modifier.fillMaxWidth();

  if (oneHandUI.state.value) {
    modifier = modifier.scale(scaleX = 1f, scaleY = -1f)
  }

  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Icon(painterResource(MR.images.ic_search), null, Modifier.padding(horizontal = DEFAULT_PADDING_HALF).size(24.dp * fontSizeSqrtMultiplier), tint = MaterialTheme.colors.secondary)
    SearchTextField(
      Modifier.weight(1f).onFocusChanged { focused = it.hasFocus }.focusRequester(focusRequester),
      placeholder = stringResource(MR.strings.search_or_paste_simplex_link),
      alwaysVisible = true,
      searchText = searchText,
      enabled = !remember { searchShowingSimplexLink }.value,
      trailingContent = null,
    ) {
      searchText.value = searchText.value.copy(it)
    }
    val hasText = remember { derivedStateOf { searchText.value.text.isNotEmpty() } }
    if (hasText.value) {
      val hideSearchOnBack: () -> Unit = { searchText.value = TextFieldValue() }
      BackHandler(onBack = hideSearchOnBack)
      KeyChangeEffect(chatModel.currentRemoteHost.value) {
        hideSearchOnBack()
      }
    } else {
      val padding = if (appPlatform.isDesktop) 0.dp else 7.dp
      if (chatModel.chats.size > 0) {
        ToggleFilterEnabledButton() 
      }
      Spacer(Modifier.width(padding))
    }
    val focusManager = LocalFocusManager.current
    val keyboardState = getKeyboardState()
    LaunchedEffect(keyboardState.value) {
      if (keyboardState.value == KeyboardState.Closed && focused) {
        focusManager.clearFocus()
      }
    }
    val view = LocalMultiplatformView()
    LaunchedEffect(Unit) {
      snapshotFlow { searchText.value.text }
        .distinctUntilChanged()
        .collect {
          val link = strHasSingleSimplexLink(it.trim())
          if (link != null) {
            // if SimpleX link is pasted, show connection dialogue
            hideKeyboard(view)
            if (link.format is Format.SimplexLink) {
              val linkText = link.simplexLinkText(link.format.linkType, link.format.smpHosts)
              searchText.value = searchText.value.copy(linkText, selection = TextRange.Zero)
            }
            searchShowingSimplexLink.value = true
            searchChatFilteredBySimplexLink.value = null
            connect(link.text, searchChatFilteredBySimplexLink) { searchText.value = TextFieldValue() }
          } else if (!searchShowingSimplexLink.value || it.isEmpty()) {
            if (it.isNotEmpty()) {
              // if some other text is pasted, enter search mode
              focusRequester.requestFocus()
            } else if (listState.layoutInfo.totalItemsCount > 0) {
              listState.scrollToItem(0)
            }
            searchShowingSimplexLink.value = false
            searchChatFilteredBySimplexLink.value = null
          }
        }
    }
  }
}

private fun connect(link: String, searchChatFilteredBySimplexLink: MutableState<String?>, cleanup: (() -> Unit)?) {
  withBGApi {
    planAndConnect(
      chatModel.remoteHostId(),
      URI.create(link),
      incognito = null,
      filterKnownContact = { searchChatFilteredBySimplexLink.value = it.id },
      filterKnownGroup = { searchChatFilteredBySimplexLink.value = it.id },
      close = null,
      cleanup = cleanup,
    )
  }
}

@Composable
private fun ErrorSettingsView() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(generalGetString(MR.strings.error_showing_content), color = MaterialTheme.colors.error, fontStyle = FontStyle.Italic)
  }
}

private var lazyListState = 0 to 0

enum class ScrollDirection {
  Up, Down, Idle
}

@Composable
private fun ChatList(chatModel: ChatModel, searchText: MutableState<TextFieldValue>, oneHandUI: SharedPreference<Boolean>) {
  val listState = rememberLazyListState(lazyListState.first, lazyListState.second)
  var scrollDirection by remember { mutableStateOf(ScrollDirection.Idle) }
  var previousIndex by remember { mutableStateOf(0) }
  var previousScrollOffset by remember { mutableStateOf(0) }

  LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
    val currentIndex = listState.firstVisibleItemIndex
    val currentScrollOffset = listState.firstVisibleItemScrollOffset
    val threshold = 25

    scrollDirection = when {
      currentIndex > previousIndex -> ScrollDirection.Down
      currentIndex < previousIndex -> ScrollDirection.Up
      currentScrollOffset > previousScrollOffset + threshold -> ScrollDirection.Down
      currentScrollOffset < previousScrollOffset - threshold -> ScrollDirection.Up
      currentScrollOffset == previousScrollOffset -> ScrollDirection.Idle
      else -> scrollDirection
    }

    previousIndex = currentIndex
    previousScrollOffset = currentScrollOffset
  }

  DisposableEffect(Unit) {
    onDispose { lazyListState = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
  }
  val showUnreadAndFavorites = remember { ChatController.appPrefs.showUnreadAndFavorites.state }.value
  val allChats = remember { chatModel.chats }
  // In some not always reproducible situations this code produce IndexOutOfBoundsException on Compose's side
  // which is related to [derivedStateOf]. Using safe alternative instead
  // val chats by remember(search, showUnreadAndFavorites) { derivedStateOf { filteredChats(showUnreadAndFavorites, search, allChats.toList()) } }
  val searchShowingSimplexLink = remember { mutableStateOf(false) }
  val searchChatFilteredBySimplexLink = remember { mutableStateOf<String?>(null) }
  val chats = filteredChats(showUnreadAndFavorites, searchShowingSimplexLink, searchChatFilteredBySimplexLink, searchText.value.text, allChats.toList())
  LazyColumnWithScrollBar(
    Modifier.fillMaxWidth(),
    listState
  ) {
    stickyHeader {
      Column(
        Modifier
          .offset {
            val y = if (searchText.value.text.isEmpty()) {
              if (oneHandUI.state.value && scrollDirection == ScrollDirection.Up) {
                0
              } else if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset else -1000
            } else {
              0
            }
            IntOffset(0, y)
          }
          .background(MaterialTheme.colors.background)
      ) {
        ChatListSearchBar(listState, searchText, searchShowingSimplexLink, searchChatFilteredBySimplexLink, oneHandUI)
        Divider()
      }
    }
    itemsIndexed(chats) { index, chat ->
      val nextChatSelected = remember(chat.id, chats) { derivedStateOf {
        chatModel.chatId.value != null && chats.getOrNull(index + 1)?.id == chatModel.chatId.value
      } }
      ChatListNavLinkView(chat, nextChatSelected, oneHandUI.state)
    }
  }
  if (chats.isEmpty() && chatModel.chats.isNotEmpty()) {
    var modifier = Modifier.fillMaxSize();

    if (oneHandUI.state.value) {
      modifier = modifier.scale(scaleX = 1f, scaleY = -1f)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
      Text(generalGetString(MR.strings.no_filtered_chats), color = MaterialTheme.colors.secondary)
    }
  }
}

private fun filteredChats(
  showUnreadAndFavorites: Boolean,
  searchShowingSimplexLink: State<Boolean>,
  searchChatFilteredBySimplexLink: State<String?>,
  searchText: String,
  chats: List<Chat>
): List<Chat> {
  val linkChatId = searchChatFilteredBySimplexLink.value
  return if (linkChatId != null) {
    chats.filter { it.id == linkChatId }
  } else {
    val s = if (searchShowingSimplexLink.value) "" else searchText.trim().lowercase()
    if (s.isEmpty() && !showUnreadAndFavorites)
      chats.filter { chat -> !chat.chatInfo.chatDeleted }
    else {
      chats.filter { chat ->
        when (val cInfo = chat.chatInfo) {
          is ChatInfo.Direct -> !chat.chatInfo.chatDeleted && (
            if (s.isEmpty()) {
              chat.id == chatModel.chatId.value || filtered(chat)
            } else {
              (viewNameContains(cInfo, s) ||
                      cInfo.contact.profile.displayName.lowercase().contains(s) ||
                      cInfo.contact.fullName.lowercase().contains(s))
            })
          is ChatInfo.Group -> if (s.isEmpty()) {
            chat.id == chatModel.chatId.value || filtered(chat) || cInfo.groupInfo.membership.memberStatus == GroupMemberStatus.MemInvited
          } else {
            viewNameContains(cInfo, s)
          }
          is ChatInfo.Local -> s.isEmpty() || viewNameContains(cInfo, s)
          is ChatInfo.ContactRequest -> s.isEmpty() || viewNameContains(cInfo, s)
          is ChatInfo.ContactConnection -> (s.isNotEmpty() && cInfo.contactConnection.localAlias.lowercase().contains(s)) || (s.isEmpty() && chat.id == chatModel.chatId.value)
          is ChatInfo.InvalidJSON -> chat.id == chatModel.chatId.value
        }
      }
    }
  }
}

private fun filtered(chat: Chat): Boolean =
  (chat.chatInfo.chatSettings?.favorite ?: false) ||
      chat.chatStats.unreadChat ||
      (chat.chatInfo.ntfsEnabled && chat.chatStats.unreadCount > 0)

private fun viewNameContains(cInfo: ChatInfo, s: String): Boolean =
  cInfo.chatViewName.lowercase().contains(s.lowercase())
