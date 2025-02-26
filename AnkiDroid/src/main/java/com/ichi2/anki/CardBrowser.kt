/****************************************************************************************
 * Copyright (c) 2010 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2014 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki

import android.content.*
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.CheckResult
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import anki.collection.OpChanges
import com.afollestad.materialdialogs.utils.MDUtil.ifNotZero
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.Previewer.Companion.toIntent
import com.ichi2.anki.UIUtils.showThemedToast
import com.ichi2.anki.browser.CardBrowserColumn
import com.ichi2.anki.browser.CardBrowserColumn.Companion.COLUMN1_KEYS
import com.ichi2.anki.browser.CardBrowserColumn.Companion.COLUMN2_KEYS
import com.ichi2.anki.browser.CardBrowserLaunchOptions
import com.ichi2.anki.browser.CardBrowserViewModel
import com.ichi2.anki.browser.CardBrowserViewModel.*
import com.ichi2.anki.browser.SaveSearchResult
import com.ichi2.anki.browser.SharedPreferencesLastDeckIdRepository
import com.ichi2.anki.browser.toCardBrowserLaunchOptions
import com.ichi2.anki.dialogs.*
import com.ichi2.anki.dialogs.CardBrowserMySearchesDialog.Companion.newInstance
import com.ichi2.anki.dialogs.CardBrowserMySearchesDialog.MySearchesDialogListener
import com.ichi2.anki.dialogs.DeckSelectionDialog.Companion.newInstance
import com.ichi2.anki.dialogs.DeckSelectionDialog.DeckSelectionListener
import com.ichi2.anki.dialogs.DeckSelectionDialog.SelectableDeck
import com.ichi2.anki.dialogs.RescheduleDialog.Companion.rescheduleMultipleCards
import com.ichi2.anki.dialogs.RescheduleDialog.Companion.rescheduleSingleCard
import com.ichi2.anki.dialogs.tags.TagsDialog
import com.ichi2.anki.dialogs.tags.TagsDialogFactory
import com.ichi2.anki.dialogs.tags.TagsDialogListener
import com.ichi2.anki.export.ActivityExportingDelegate
import com.ichi2.anki.export.ExportDialogFragment
import com.ichi2.anki.export.ExportDialogsFactory
import com.ichi2.anki.export.ExportDialogsFactoryProvider
import com.ichi2.anki.introduction.hasCollectionStoragePermissions
import com.ichi2.anki.model.CardStateFilter
import com.ichi2.anki.model.CardsOrNotes
import com.ichi2.anki.model.CardsOrNotes.*
import com.ichi2.anki.model.SortType
import com.ichi2.anki.pages.CardInfo.Companion.toIntent
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.anki.previewer.PreviewerFragment
import com.ichi2.anki.receiver.SdCardReceiver
import com.ichi2.anki.servicelayer.NoteService.isMarked
import com.ichi2.anki.servicelayer.avgIntervalOfNote
import com.ichi2.anki.servicelayer.rescheduleCards
import com.ichi2.anki.servicelayer.resetCards
import com.ichi2.anki.servicelayer.totalLapsesOfNote
import com.ichi2.anki.servicelayer.totalReviewsForNote
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.ui.BasicItemSelectedListener
import com.ichi2.anki.utils.SECONDS_PER_DAY
import com.ichi2.anki.utils.roundedTimeSpanUnformatted
import com.ichi2.anki.widgets.DeckDropDownAdapter.SubtitleListener
import com.ichi2.annotations.NeedsTest
import com.ichi2.async.*
import com.ichi2.libanki.*
import com.ichi2.libanki.Collection
import com.ichi2.ui.CardBrowserSearchView
import com.ichi2.ui.FixedTextView
import com.ichi2.utils.*
import com.ichi2.utils.HandlerUtils.postDelayedOnNewHandler
import com.ichi2.utils.TagsUtil.getUpdatedTags
import com.ichi2.widget.WidgetStatus.updateInBackground
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.ankiweb.rsdroid.RustCleanup
import timber.log.Timber
import java.util.*
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.ceil

@Suppress("LeakingThis")
// The class is only 'open' due to testing
@KotlinCleanup("scan through this class and add attributes - in process")
open class CardBrowser :
    NavigationDrawerActivity(),
    SubtitleListener,
    DeckSelectionListener,
    TagsDialogListener,
    ChangeManager.Subscriber,
    ExportDialogsFactoryProvider {
    override fun onDeckSelected(deck: SelectableDeck?) {
        deck?.let {
            val deckId = deck.deckId
            deckSpinnerSelection!!.initializeActionBarDeckSpinner(getColUnsafe, this.supportActionBar!!)
            launchCatchingTask { selectDeckAndSave(deckId) }
        }
    }

    private enum class TagsDialogListenerAction {
        FILTER, EDIT_TAGS
    }

    lateinit var viewModel: CardBrowserViewModel

    private var launchOptions: CardBrowserLaunchOptions? = null

    /** List of cards in the browser.
     * When the list is changed, the position member of its elements should get changed. */
    private val mCards get() = viewModel.cards
    var deckSpinnerSelection: DeckSpinnerSelection? = null

    @VisibleForTesting
    lateinit var cardsListView: ListView
    private var mSearchView: CardBrowserSearchView? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    lateinit var cardsAdapter: MultiColumnListAdapter

    private var mSearchTerms
        get() = viewModel.searchTerms
        set(value) { viewModel.searchTerms = value }
    private lateinit var mTagsDialogFactory: TagsDialogFactory
    private var mSearchItem: MenuItem? = null
    private var mSaveSearchItem: MenuItem? = null
    private var mMySearchesItem: MenuItem? = null
    private var mPreviewItem: MenuItem? = null
    private var mUndoSnackbar: Snackbar? = null

    private var renderBrowserQAJob: Job? = null

    private lateinit var mExportingDelegate: ActivityExportingDelegate

    // card that was clicked (not marked)
    private var mCurrentCardId
        get() = viewModel.currentCardId
        set(value) { viewModel.currentCardId = value }

    // DEFECT: Doesn't need to be a local
    private var mTagsDialogListenerAction: TagsDialogListenerAction? = null

    private var onEditCardActivityResult = registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
        Timber.d("onEditCardActivityResult: resultCode=%d", result.resultCode)
        if (result.resultCode == DeckPicker.RESULT_DB_ERROR) {
            closeCardBrowser(DeckPicker.RESULT_DB_ERROR)
        }
        if (result.resultCode != RESULT_CANCELED) {
            Timber.i("CardBrowser:: CardBrowser: Saving card...")
            launchCatchingTask { saveEditedCard() }
        }
        val data = result.data
        if (data != null &&
            (data.getBooleanExtra(NoteEditor.RELOAD_REQUIRED_EXTRA_KEY, false) || data.getBooleanExtra(NoteEditor.NOTE_CHANGED_EXTRA_KEY, false))
        ) {
            Timber.d("Reloading Card Browser due to activity result")
            // if reloadRequired or noteChanged flag was sent from note editor then reload card list
            mShouldRestoreScroll = true
            forceRefreshSearch()
            // in use by reviewer?
            if (reviewerCardId == mCurrentCardId) {
                mReloadRequired = true
            }
        }
        invalidateOptionsMenu() // maybe the availability of undo changed
    }
    private var onAddNoteActivityResult = registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
        Timber.d("onAddNoteActivityResult: resultCode=%d", result.resultCode)
        if (result.resultCode == DeckPicker.RESULT_DB_ERROR) {
            closeCardBrowser(DeckPicker.RESULT_DB_ERROR)
        }
        if (result.resultCode == RESULT_OK) {
            forceRefreshSearch(useSearchTextValue = true)
        }
        invalidateOptionsMenu() // maybe the availability of undo changed
    }
    private var onPreviewCardsActivityResult = registerForActivityResult(StartActivityForResult()) { result: ActivityResult ->
        Timber.d("onPreviewCardsActivityResult: resultCode=%d", result.resultCode)
        if (result.resultCode == DeckPicker.RESULT_DB_ERROR) {
            closeCardBrowser(DeckPicker.RESULT_DB_ERROR)
        }
        // Previewing can now perform an "edit", so it can pass on a reloadRequired
        val data = result.data
        if (data != null &&
            (data.getBooleanExtra(NoteEditor.RELOAD_REQUIRED_EXTRA_KEY, false) || data.getBooleanExtra(NoteEditor.NOTE_CHANGED_EXTRA_KEY, false))
        ) {
            forceRefreshSearch()
            if (reviewerCardId == mCurrentCardId) {
                mReloadRequired = true
            }
        }
        invalidateOptionsMenu() // maybe the availability of undo changed
    }
    private var mLastRenderStart: Long = 0
    private lateinit var mActionBarTitle: TextView
    private var mReloadRequired = false

    private var mLastSelectedPosition
        get() = viewModel.lastSelectedPosition
        set(value) { viewModel.lastSelectedPosition = value }
    private var mActionBarMenu: Menu? = null
    private var mOldCardId: CardId = 0
    private var mOldCardTopOffset = 0
    private var mShouldRestoreScroll = false
    private var mPostAutoScroll = false
    private val mOnboarding = Onboarding.CardBrowser(this)

    /**
     * Broadcast that informs us when the sd card is about to be unmounted
     */
    private var mUnmountReceiver: BroadcastReceiver? = null

    init {
        ChangeManager.subscribe(this)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    fun changeCardOrder(which: SortType) {
        when (viewModel.changeCardOrder(which)) {
            ChangeCardOrderResult.OrderChange -> { searchCards() }
            ChangeCardOrderResult.DirectionChange -> {
                mCards.reverse()
                updateList()
            }
            null -> {}
        }
    }

    @VisibleForTesting
    internal val mMySearchesDialogListener: MySearchesDialogListener = object : MySearchesDialogListener {

        override fun onSelection(searchName: String) {
            Timber.d("OnSelection using search named: %s", searchName)
            launchCatchingTask {
                viewModel.savedSearches()[searchName]?.apply {
                    Timber.d("OnSelection using search terms: %s", this)
                    mSearchTerms = this
                    mSearchView!!.setQuery(this, false)
                    mSearchItem!!.expandActionView()
                    searchCards()
                }
            }
        }

        override fun onRemoveSearch(searchName: String) {
            Timber.d("OnRemoveSelection using search named: %s", searchName)
            launchCatchingTask {
                val updatedFilters = viewModel.removeSavedSearch(searchName)
                if (updatedFilters.isEmpty()) {
                    mMySearchesItem!!.isVisible = false
                }
            }
        }

        override fun onSaveSearch(searchName: String, searchTerms: String?) {
            if (searchTerms == null) {
                return
            }
            if (searchName.isEmpty()) {
                showSnackbar(
                    R.string.card_browser_list_my_searches_new_search_error_empty_name,
                    Snackbar.LENGTH_SHORT
                )
                return
            }
            launchCatchingTask {
                when (viewModel.saveSearch(searchName, searchTerms)) {
                    SaveSearchResult.ALREADY_EXISTS -> showSnackbar(
                        R.string.card_browser_list_my_searches_new_search_error_dup,
                        Snackbar.LENGTH_SHORT
                    )
                    SaveSearchResult.SUCCESS -> {
                        mSearchView!!.setQuery("", false)
                        mMySearchesItem!!.isVisible = true
                    }
                }
            }
        }
    }

    private fun onSearch() {
        mSearchTerms = mSearchView!!.query.toString()
        if (mSearchTerms.isEmpty()) {
            mSearchView!!.queryHint = resources.getString(R.string.deck_conf_cram_search)
        }
        searchCards()
    }

    private val selectedCardIds: List<CardId>
        get() = viewModel.selectedCardIds

    private fun canPerformCardInfo(): Boolean {
        return viewModel.selectedRowCount() == 1
    }

    private fun canPerformMultiSelectEditNote(): Boolean {
        // The noteId is not currently available. Only allow if a single card is selected for now.
        return viewModel.selectedRowCount() == 1
    }

    /**
     * Change Deck
     * @param did Id of the deck
     */
    @VisibleForTesting
    fun moveSelectedCardsToDeck(did: DeckId): Job {
        return launchCatchingTask {
            val changed = withProgress {
                undoableOp {
                    setDeck(selectedCardIds, did)
                }
            }
            showUndoSnackbar(TR.browsingCardsUpdated(changed.count))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        // must be called after showedActivityFailedScreen
        viewModel = createViewModel()

        mTagsDialogFactory = TagsDialogFactory(this).attachToActivity<TagsDialogFactory>(this)
        mExportingDelegate = ActivityExportingDelegate(this) { getColUnsafe }
        super.onCreate(savedInstanceState)
        if (wasLoadedFromExternalTextActionItem() && !hasCollectionStoragePermissions()) {
            // we need to do this as DeckPicker still contains app init logic/upgrade logic
            Timber.w("'Card Browser' Action item pressed before storage permissions granted.")
            showThemedToast(
                this,
                getString(R.string.intent_handler_failed_no_storage_permission),
                false
            )
            displayDeckPickerForPermissionsDialog()
            return
        }
        launchOptions = intent?.toCardBrowserLaunchOptions() // must be called after super.onCreate()
        setContentView(R.layout.card_browser)
        initNavigationDrawer(findViewById(android.R.id.content))
        // initialize the lateinit variables
        // Load reference to action bar title
        mActionBarTitle = findViewById(R.id.toolbar_title)
        cardsListView = findViewById(R.id.card_browser_list)
        val preferences = baseContext.sharedPrefs()
        // get the font and font size from the preferences
        val sflRelativeFontSize =
            preferences.getInt("relativeCardBrowserFontSize", DEFAULT_FONT_SIZE_RATIO)
        val columnsContent = arrayOf(
            COLUMN1_KEYS[viewModel.column1Index],
            COLUMN2_KEYS[viewModel.column2Index]
        )
        // make a new list adapter mapping the data in mCards to column1 and column2 of R.layout.card_item_browser
        cardsAdapter = MultiColumnListAdapter(
            this,
            R.layout.card_item_browser,
            columnsContent,
            intArrayOf(R.id.card_sfld, R.id.card_column2),
            sflRelativeFontSize
        )
        // link the adapter to the main mCardsListView
        cardsListView.adapter = cardsAdapter
        // make the items (e.g. question & answer) render dynamically when scrolling
        cardsListView.setOnScrollListener(RenderOnScroll())
        startLoadingCollection()

        when (val options = launchOptions) {
            is CardBrowserLaunchOptions.DeepLink -> {
                mSearchTerms = options.search
                searchCards()
            }
            is CardBrowserLaunchOptions.SearchQueryJs -> {
                mSearchTerms = options.search
                if (options.allDecks) {
                    onDeckSelected(SelectableDeck(ALL_DECKS_ID, getString(R.string.card_browser_all_decks)))
                }
                searchCards()
            }
            else -> {} // Context Menu handled in onCreateOptionsMenu
        }
        mExportingDelegate.onRestoreInstanceState(savedInstanceState)

        // Selected cards aren't restored on activity recreation,
        // so it is necessary to dismiss the change deck dialog
        val dialogFragment = supportFragmentManager.findFragmentByTag(DIALOG_FRAGMENT_TAG)
        if (dialogFragment is DeckSelectionDialog) {
            if (dialogFragment.requireArguments().getBoolean(CHANGE_DECK_KEY, false)) {
                Timber.d("onCreate(): Change deck dialog dismissed")
                dialogFragment.dismiss()
            }
        }
        mOnboarding.onCreate()

        viewModel.isTruncatedFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { cardsAdapter.notifyDataSetChanged() }
            .launchIn(lifecycleScope)

        viewModel.cardsOrNotesFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { runOnUiThread { searchCards() } }
            .launchIn(lifecycleScope)

        viewModel.searchQueryExpandedFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { searchQueryExpanded ->
                Timber.d("query expansion changed: %b", searchQueryExpanded)
                if (searchQueryExpanded) {
                    runOnUiThread { mSearchItem?.expandActionView() }
                } else {
                    runOnUiThread { mSearchItem?.collapseActionView() }
                    // invalidate options menu so that disappeared icons would appear again
                    invalidateOptionsMenu()
                }
            }
            .launchIn(lifecycleScope)

        viewModel.selectedRowsFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { runOnUiThread { onSelectionChanged() } }
            .launchIn(lifecycleScope)

        viewModel.column1IndexFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { index -> cardsAdapter.updateMapping { it[0] = COLUMN1_KEYS[index] } }
            .launchIn(lifecycleScope)

        viewModel.column2IndexFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { index -> cardsAdapter.updateMapping { it[1] = COLUMN2_KEYS[index] } }
            .launchIn(lifecycleScope)

        viewModel.filterQueryFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { filterQuery ->
                mSearchView!!.setQuery("", false)
                mSearchTerms = filterQuery
                mSearchView!!.setQuery(mSearchTerms, true)
                searchCards()
            }
            .launchIn(lifecycleScope)

        viewModel.deckIdFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .filterNotNull()
            .onEach { deckId ->
                // this handles ALL_DECKS_ID
                deckSpinnerSelection!!.selectDeckById(deckId, false)
                searchCards()
            }
            .launchIn(lifecycleScope)

        viewModel.canSaveSearchFlow
            .onEach { canSave -> runOnUiThread { mSaveSearchItem?.isVisible = canSave } }
            .launchIn(lifecycleScope)

        viewModel.isInMultiSelectModeFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { inMultiSelect ->
                if (inMultiSelect) {
                    // Turn on Multi-Select Mode so that the user can select multiple cards at once.
                    Timber.d("load multiselect mode")
                    // show title and hide spinner
                    mActionBarTitle.visibility = View.VISIBLE
                    deckSpinnerSelection!!.setSpinnerVisibility(View.GONE)
                } else {
                    Timber.d("end multiselect mode")
                    // If view which was originally selected when entering multi-select is visible then maintain its position
                    val view = cardsListView.getChildAt(mLastSelectedPosition - cardsListView.firstVisiblePosition)
                    view?.let { recenterListView(it) }
                    // update adapter to remove check boxes
                    cardsAdapter.notifyDataSetChanged()
                    deckSpinnerSelection!!.setSpinnerVisibility(View.VISIBLE)
                    mActionBarTitle.visibility = View.GONE
                }
                // reload the actionbar using the multi-select mode actionbar
                invalidateOptionsMenu()
            }
            .launchIn(lifecycleScope)

        viewModel.initCompletedFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .onEach { completed -> if (completed) searchCards() }
            .launchIn(lifecycleScope)
    }

    fun searchWithFilterQuery(filterQuery: String) = launchCatchingTask {
        viewModel.setFilterQuery(filterQuery)
    }

    // Finish initializing the activity after the collection has been correctly loaded
    override fun onCollectionLoaded(col: Collection) {
        super.onCollectionLoaded(col)
        Timber.d("onCollectionLoaded()")
        registerExternalStorageListener()
        mCards.reset()
        // Create a spinner for column 1
        findViewById<Spinner>(R.id.browser_column1_spinner).apply {
            adapter = ArrayAdapter.createFromResource(
                this@CardBrowser,
                R.array.browser_column1_headings,
                android.R.layout.simple_spinner_item
            ).apply {
                setDropDownViewResource(R.layout.spinner_custom_layout)
            }
            onItemSelectedListener = BasicItemSelectedListener { pos, _ ->
                viewModel.setColumn1Index(pos)
            }
            setSelection(viewModel.column1Index)
        }
        // Setup the column 2 heading as a spinner so that users can easily change the column type
        findViewById<Spinner>(R.id.browser_column2_spinner).apply {
            adapter = ArrayAdapter.createFromResource(
                this@CardBrowser,
                R.array.browser_column2_headings,
                android.R.layout.simple_spinner_item
            ).apply {
                // The custom layout for the adapter is used to prevent the overlapping of various interactive components on the screen
                setDropDownViewResource(R.layout.spinner_custom_layout)
            }
            // Create a new list adapter with updated column map any time the user changes the column
            onItemSelectedListener = BasicItemSelectedListener { pos, _ ->
                viewModel.setColumn2Index(pos)
            }
            setSelection(viewModel.column2Index)
        }

        cardsListView.setOnItemClickListener { _: AdapterView<*>?, view: View?, position: Int, _: Long ->
            if (viewModel.isInMultiSelectMode) {
                // click on whole cell triggers select
                val cb = view!!.findViewById<CheckBox>(R.id.card_checkbox)
                cb.toggle()
                viewModel.toggleRowSelectionAtPosition(position)
            } else {
                // load up the card selected on the list
                val clickedCardId = viewModel.getCardIdAtPosition(position)
                saveScrollingState(position)
                openNoteEditorForCard(clickedCardId)
            }
        }
        @KotlinCleanup("helper function for min/max range")
        cardsListView.setOnItemLongClickListener { _: AdapterView<*>?, view: View?, position: Int, _: Long ->
            if (viewModel.isInMultiSelectMode) {
                viewModel.selectRowsBetweenPositions(mLastSelectedPosition, position)
            } else {
                mLastSelectedPosition = position
                saveScrollingState(position)

                // click on whole cell triggers select
                val cb = view!!.findViewById<CheckBox>(R.id.card_checkbox)
                cb.toggle()
                viewModel.toggleRowSelectionAtPosition(position)
                recenterListView(view)
                cardsAdapter.notifyDataSetChanged()
            }
            true
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        deckSpinnerSelection = DeckSpinnerSelection(
            this,
            findViewById(R.id.toolbar_spinner),
            showAllDecks = true,
            alwaysShowDefault = false,
            showFilteredDecks = true
        ).apply {
            initializeActionBarDeckSpinner(col, supportActionBar!!)
            launchCatchingTask { selectDeckById(viewModel.deckId ?: ALL_DECKS_ID, false) }
        }
    }

    suspend fun selectDeckAndSave(deckId: DeckId) {
        viewModel.setDeckId(deckId)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // NOTE: These are all active when typing in the search - doesn't matter as all need CTRL
        when (keyCode) {
            KeyEvent.KEYCODE_A -> {
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+A - Select All")
                    viewModel.selectAll()
                    return true
                }
            }
            KeyEvent.KEYCODE_E -> {
                // Ctrl+Shift+E: Export (TODO)
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+E: Add Note")
                    launchCatchingTask { addNoteFromCardBrowser() }
                    return true
                }
            }
            KeyEvent.KEYCODE_D -> {
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+D: Change Deck")
                    showChangeDeckDialog()
                    return true
                }
            }
            KeyEvent.KEYCODE_K -> {
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+K: Toggle Mark")
                    toggleMark()
                    return true
                }
            }
            KeyEvent.KEYCODE_R -> {
                if (event.isCtrlPressed && event.isAltPressed) {
                    Timber.i("Ctrl+Alt+R - Reschedule")
                    rescheduleSelectedCards()
                    return true
                }
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                Timber.i("Delete pressed - Delete Selected Note")
                deleteSelectedNotes()
                return true
            }
            KeyEvent.KEYCODE_F -> {
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+F - Find notes")
                    mSearchItem?.expandActionView()
                    return true
                }
            }
            KeyEvent.KEYCODE_P -> {
                if (event.isShiftPressed && event.isCtrlPressed) {
                    Timber.i("Ctrl+Shift+P - Preview")
                    onPreview()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** All the notes of the selected cards will be marked
     * If one or more card is unmarked, all will be marked,
     * otherwise, they will be unmarked  */
    @NeedsTest("Test that the mark get toggled as expected for a list of selected cards")
    @VisibleForTesting
    fun toggleMark() = launchCatchingTask {
        withProgress { viewModel.toggleMark(selectedCardIds) }
        cardsAdapter.notifyDataSetChanged()
    }

    @VisibleForTesting
    suspend fun selectAllDecks() {
        viewModel.setDeckId(ALL_DECKS_ID)
    }

    /** Opens the note editor for a card.
     * We use the Card ID to specify the preview target  */
    private fun openNoteEditorForCard(cardId: CardId) {
        mCurrentCardId = cardId
        cardBrowserCard = getColUnsafe.getCard(mCurrentCardId)
        // start note editor using the card we just loaded
        val editCard = Intent(this, NoteEditor::class.java)
            .putExtra(NoteEditor.EXTRA_CALLER, NoteEditor.CALLER_CARDBROWSER_EDIT)
            .putExtra(NoteEditor.EXTRA_CARD_ID, cardBrowserCard!!.id)
        onEditCardActivityResult.launch(editCard)
        // #6432 - FIXME - onCreateOptionsMenu crashes if receiving an activity result from edit card when in multiselect
        viewModel.endMultiSelectMode()
    }

    private fun openNoteEditorForCurrentlySelectedNote() {
        try {
            // Just select the first one. It doesn't particularly matter if there's a multiselect occurring.
            openNoteEditorForCard(selectedCardIds[0])
        } catch (e: Exception) {
            Timber.w(e, "Error Opening Note Editor")
            showSnackbar(
                R.string.multimedia_editor_something_wrong,
                Snackbar.LENGTH_LONG
            )
        }
    }

    override fun onStop() {
        // cancel rendering the question and answer, which has shared access to mCards
        super.onStop()
        if (!isFinishing) {
            updateInBackground(this)
        }
    }

    override fun onDestroy() {
        invalidate()
        super.onDestroy()
        if (mUnmountReceiver != null) {
            unregisterReceiver(mUnmountReceiver)
        }
    }

    override fun onBackPressed() {
        when {
            isDrawerOpen -> super.onBackPressed()
            viewModel.isInMultiSelectMode -> viewModel.endMultiSelectMode()
            else -> {
                Timber.i("Back key pressed")
                val data = Intent()
                // Add reload flag to result intent so that schedule reset when returning to note editor
                data.putExtra(NoteEditor.RELOAD_REQUIRED_EXTRA_KEY, mReloadRequired)
                closeCardBrowser(RESULT_OK, data)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // If the user entered something into the search, but didn't press "search", clear this.
        // It's confusing if the bar is shown with a query that does not relate to the data on the screen
        viewModel.removeUnsubmittedInput()
        if (mPostAutoScroll) {
            mPostAutoScroll = false
        }
    }

    override fun onResume() {
        super.onResume()
        selectNavigationItem(R.id.nav_browser)
    }

    @KotlinCleanup("Add a few variables to get rid of the !!")
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Timber.d("onCreateOptionsMenu()")
        mActionBarMenu = menu
        if (!viewModel.isInMultiSelectMode) {
            // restore drawer click listener and icon
            restoreDrawerIcon()
            menuInflater.inflate(R.menu.card_browser, menu)
            mSaveSearchItem = menu.findItem(R.id.action_save_search)
            mSaveSearchItem?.isVisible = false // the searchview's query always starts empty.
            mMySearchesItem = menu.findItem(R.id.action_list_my_searches)
            val savedFiltersObj = viewModel.savedSearchesUnsafe(getColUnsafe)
            mMySearchesItem!!.isVisible = savedFiltersObj.size > 0
            mSearchItem = menu.findItem(R.id.action_search)
            mSearchItem!!.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    viewModel.setSearchQueryExpanded(true)
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    viewModel.setSearchQueryExpanded(false)
                    // SearchView doesn't support empty queries so we always reset the search when collapsing
                    mSearchTerms = ""
                    mSearchView!!.setQuery(mSearchTerms, false)
                    searchCards()
                    return true
                }
            })
            mSearchView = mSearchItem!!.actionView as CardBrowserSearchView
            mSearchView!!.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(newText: String): Boolean {
                    if (mSearchView!!.shouldIgnoreValueChange()) {
                        return true
                    }
                    viewModel.updateQueryText(newText)
                    return true
                }

                override fun onQueryTextSubmit(query: String): Boolean {
                    onSearch()
                    mSearchView!!.clearFocus()
                    return true
                }
            })
            // Fixes #6500 - keep the search consistent if coming back from note editor
            // Fixes #9010 - consistent search after drawer change calls invalidateOptionsMenu
            if (!viewModel.tempSearchQuery.isNullOrEmpty() || mSearchTerms.isNotEmpty()) {
                mSearchItem!!.expandActionView() // This calls mSearchView.setOnSearchClickListener
                val toUse =
                    if (!viewModel.tempSearchQuery.isNullOrEmpty()) viewModel.tempSearchQuery else mSearchTerms
                mSearchView!!.setQuery(toUse!!, false)
            }
            mSearchView!!.setOnSearchClickListener {
                // Provide SearchView with the previous search terms
                mSearchView!!.setQuery(mSearchTerms, false)
            }
        } else {
            // multi-select mode
            menuInflater.inflate(R.menu.card_browser_multiselect, menu)
            showBackIcon()
            increaseHorizontalPaddingOfOverflowMenuIcons(menu)
        }
        mActionBarMenu?.findItem(R.id.action_undo)?.run {
            isVisible = getColUnsafe.undoAvailable()
            title = getColUnsafe.undoLabel()
        }

        launchOptions?.let { options ->
            if (options !is CardBrowserLaunchOptions.SystemContextMenu) return@let
            // Fill in the search.
            Timber.i("CardBrowser :: Called with search intent: %s", launchOptions.toString())
            searchWithFilterQuery(options.search.toString())
            launchOptions = null
        }

        mPreviewItem = menu.findItem(R.id.action_preview)
        onSelectionChanged()
        updatePreviewMenuItem()
        return super.onCreateOptionsMenu(menu)
    }

    override fun onNavigationPressed() {
        if (viewModel.isInMultiSelectMode) {
            viewModel.endMultiSelectMode()
        } else {
            super.onNavigationPressed()
        }
    }

    private fun displayDeckPickerForPermissionsDialog() {
        // TODO: Combine this with class: IntentHandler after both are well-tested
        val deckPicker = Intent(this, DeckPicker::class.java)
        deckPicker.action = Intent.ACTION_MAIN
        deckPicker.addCategory(Intent.CATEGORY_LAUNCHER)
        deckPicker.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(deckPicker)
        finish()
        this.setResult(RESULT_CANCELED)
    }

    private fun wasLoadedFromExternalTextActionItem(): Boolean {
        val intent = this.intent ?: return false
        // API 23: Replace with Intent.ACTION_PROCESS_TEXT
        return "android.intent.action.PROCESS_TEXT".equals(intent.action, ignoreCase = true)
    }

    private fun updatePreviewMenuItem() {
        mPreviewItem?.isVisible = viewModel.rowCount > 0
    }

    private fun updateMultiselectMenu() {
        Timber.d("updateMultiselectMenu()")
        if (mActionBarMenu == null || mActionBarMenu!!.findItem(R.id.action_suspend_card) == null) {
            return
        }
        if (viewModel.hasSelectedAnyRows()) {
            mActionBarMenu!!.findItem(R.id.action_suspend_card).apply {
                title = TR.browsingToggleSuspend()
                setIcon(R.drawable.ic_suspend)
            }
            mActionBarMenu!!.findItem(R.id.action_mark_card).apply {
                title = TR.browsingToggleMark()
                setIcon(R.drawable.ic_star_border_white)
            }
        }
        mActionBarMenu!!.findItem(R.id.action_export_selected).apply {
            this.title = if (viewModel.cardsOrNotes == CARDS) {
                resources.getQuantityString(
                    R.plurals.card_browser_export_cards,
                    viewModel.selectedRowCount()
                )
            } else {
                resources.getQuantityString(
                    R.plurals.card_browser_export_notes,
                    viewModel.selectedRowCount()
                )
            }
        }
        mActionBarMenu!!.findItem(R.id.action_delete_card).apply {
            this.title = if (viewModel.cardsOrNotes == CARDS) {
                resources.getQuantityString(
                    R.plurals.card_browser_delete_cards,
                    viewModel.selectedRowCount()
                )
            } else {
                resources.getQuantityString(
                    R.plurals.card_browser_delete_notes,
                    viewModel.selectedRowCount()
                )
            }
        }
        mActionBarMenu!!.findItem(R.id.action_select_all).isVisible = !hasSelectedAllCards()
        // Note: Theoretically should not happen, as this should kick us back to the menu
        mActionBarMenu!!.findItem(R.id.action_select_none).isVisible =
            viewModel.hasSelectedAnyRows()
        mActionBarMenu!!.findItem(R.id.action_edit_note).isVisible = canPerformMultiSelectEditNote()
        mActionBarMenu!!.findItem(R.id.action_view_card_info).isVisible = canPerformCardInfo()
    }

    private fun hasSelectedAllCards(): Boolean {
        return viewModel.selectedRowCount() >= viewModel.rowCount // must handle 0.
    }

    private fun updateFlagForSelectedRows(flag: Flag) = launchCatchingTask {
        updateSelectedCardsFlag(flag)
    }

    /**
     * Sets the flag for selected cards, default norm of flags are as:
     *
     * 0: No Flag, 1: RED, 2: ORANGE, 3: GREEN
     * 4: BLUE, 5: PINK, 6: Turquoise, 7: PURPLE
     *
     */
    @VisibleForTesting
    suspend fun updateSelectedCardsFlag(flag: Flag) {
        // list of cards with updated flags
        val updatedCards = withProgress { viewModel.updateSelectedCardsFlag(flag) }
        // TODO: try to offload the cards processing in updateCardsInList() on a background thread,
        // otherwise it could hang the main thread
        updateCardsInList(updatedCards)
        invalidateOptionsMenu() // maybe the availability of undo changed
        if (updatedCards.any { card -> card.id == reviewerCardId }) {
            mReloadRequired = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when {
            drawerToggle.onOptionsItemSelected(item) -> return true

            // dismiss undo-snackbar if shown to avoid race condition
            // (when another operation will be performed on the model, it will undo the latest operation)
            mUndoSnackbar != null && mUndoSnackbar!!.isShown -> mUndoSnackbar!!.dismiss()
        }

        when (item.itemId) {
            android.R.id.home -> {
                viewModel.endMultiSelectMode()
                return true
            }
            R.id.action_add_note_from_card_browser -> {
                addNoteFromCardBrowser()
                return true
            }
            R.id.action_save_search -> {
                val searchTerms = mSearchView!!.query.toString()
                showDialogFragment(
                    newInstance(
                        null,
                        mMySearchesDialogListener,
                        searchTerms,
                        CardBrowserMySearchesDialog.CARD_BROWSER_MY_SEARCHES_TYPE_SAVE
                    )
                )
                return true
            }
            R.id.action_list_my_searches -> {
                launchCatchingTask {
                    val savedFilters = viewModel.savedSearches()
                    showDialogFragment(
                        newInstance(
                            savedFilters,
                            mMySearchesDialogListener,
                            "",
                            CardBrowserMySearchesDialog.CARD_BROWSER_MY_SEARCHES_TYPE_LIST
                        )
                    )
                }

                return true
            }
            R.id.action_sort_by_size -> {
                showDialogFragment(
                    // TODO: move this into the ViewModel
                    CardBrowserOrderDialog.newInstance { dialog: DialogInterface, which: Int ->
                        dialog.dismiss()
                        changeCardOrder(SortType.fromCardBrowserLabelIndex(which))
                    }
                )
                return true
            }

            @NeedsTest("filter-marked query needs testing")
            R.id.action_show_marked -> {
                launchCatchingTask { viewModel.searchForMarkedNotes() }
                return true
            }

            @NeedsTest("filter-suspended query needs testing")
            R.id.action_show_suspended -> {
                launchCatchingTask { viewModel.searchForSuspendedCards() }
                return true
            }
            R.id.action_search_by_tag -> {
                showFilterByTagsDialog()
                return true
            }
            R.id.action_flag_zero -> {
                updateFlagForSelectedRows(Flag.NONE)
                return true
            }
            R.id.action_flag_one -> {
                updateFlagForSelectedRows(Flag.RED)
                return true
            }
            R.id.action_flag_two -> {
                updateFlagForSelectedRows(Flag.ORANGE)
                return true
            }
            R.id.action_flag_three -> {
                updateFlagForSelectedRows(Flag.GREEN)
                return true
            }
            R.id.action_flag_four -> {
                updateFlagForSelectedRows(Flag.BLUE)
                return true
            }
            R.id.action_flag_five -> {
                updateFlagForSelectedRows(Flag.PINK)
                return true
            }
            R.id.action_flag_six -> {
                updateFlagForSelectedRows(Flag.TURQUOISE)
                return true
            }
            R.id.action_flag_seven -> {
                updateFlagForSelectedRows(Flag.PURPLE)
                return true
            }
            R.id.action_select_flag_zero -> {
                filterByFlag(Flag.NONE)
                return true
            }
            R.id.action_select_flag_one -> {
                filterByFlag(Flag.RED)
                return true
            }
            R.id.action_select_flag_two -> {
                filterByFlag(Flag.ORANGE)
                return true
            }
            R.id.action_select_flag_three -> {
                filterByFlag(Flag.GREEN)
                return true
            }
            R.id.action_select_flag_four -> {
                filterByFlag(Flag.BLUE)
                return true
            }
            R.id.action_select_flag_five -> {
                filterByFlag(Flag.PINK)
                return true
            }
            R.id.action_select_flag_six -> {
                filterByFlag(Flag.TURQUOISE)
                return true
            }
            R.id.action_select_flag_seven -> {
                filterByFlag(Flag.PURPLE)
                return true
            }
            R.id.action_delete_card -> {
                deleteSelectedNotes()
                return true
            }
            R.id.action_mark_card -> {
                toggleMark()
                return true
            }
            R.id.action_suspend_card -> {
                suspendCards()
                return true
            }
            R.id.action_change_deck -> {
                showChangeDeckDialog()
                return true
            }
            R.id.action_undo -> {
                Timber.w("CardBrowser:: Undo pressed")
                onUndo()
                return true
            }
            R.id.action_select_none -> {
                viewModel.selectNone()
                return true
            }
            R.id.action_select_all -> {
                viewModel.selectAll()
                return true
            }
            R.id.action_preview -> {
                onPreview()
                return true
            }
            R.id.action_reset_cards_progress -> {
                Timber.i("NoteEditor:: Reset progress button pressed")
                onResetProgress()
                return true
            }
            R.id.action_reschedule_cards -> {
                Timber.i("CardBrowser:: Reschedule button pressed")
                rescheduleSelectedCards()
                return true
            }
            R.id.action_reposition_cards -> {
                Timber.i("CardBrowser:: Reposition button pressed")

                // `selectedCardIds` getter does alot of work so save it in a val beforehand
                val selectedCardIds = selectedCardIds
                // Only new cards may be repositioned (If any non-new found show error dialog and return false)
                if (selectedCardIds.any { getColUnsafe.getCard(it).queue != Consts.QUEUE_TYPE_NEW }) {
                    showDialogFragment(
                        SimpleMessageDialog.newInstance(
                            title = getString(R.string.vague_error),
                            message = getString(R.string.reposition_card_not_new_error),
                            reload = false
                        )
                    )
                    return false
                }
                val repositionDialog = IntegerDialog().apply {
                    setArgs(
                        title = this@CardBrowser.getString(R.string.reposition_card_dialog_title),
                        prompt = this@CardBrowser.getString(R.string.reposition_card_dialog_message),
                        digits = 5
                    )
                    setCallbackRunnable(::repositionCardsNoValidation)
                }
                showDialogFragment(repositionDialog)
                return true
            }
            R.id.action_edit_note -> {
                openNoteEditorForCurrentlySelectedNote()
                return super.onOptionsItemSelected(item)
            }
            R.id.action_view_card_info -> {
                viewModel.cardInfoDestination?.let { destination ->
                    val intent: Intent = destination.toIntent(this)
                    startActivity(intent)
                }
                return true
            }
            R.id.action_edit_tags -> {
                showEditTagsDialog()
            }
            R.id.action_open_options -> {
                showOptionsDialog()
            }
            R.id.action_export_selected -> {
                exportSelected()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun exportDialogsFactory(): ExportDialogsFactory = mExportingDelegate.dialogsFactory

    private fun exportSelected() = launchCatchingTask {
        val (type, selectedIds) = viewModel.getSelectionExportData() ?: return@launchCatchingTask
        ExportDialogFragment.newInstance(type, selectedIds).show(supportFragmentManager, "exportDialog")
    }

    private fun deleteSelectedNotes() = launchCatchingTask {
        withProgress(R.string.deleting_selected_notes) {
            viewModel.deleteSelectedNotes()
        }.ifNotZero { noteCount ->
            val deletedMessage = resources.getQuantityString(R.plurals.card_browser_cards_deleted, noteCount, noteCount)
            showUndoSnackbar(deletedMessage)
        }
    }

    @VisibleForTesting
    fun onUndo() {
        launchCatchingTask {
            undoAndShowSnackbar()
        }
    }

    private fun onResetProgress() {
        // Show confirmation dialog before resetting card progress
        val dialog = ConfirmationDialog()
        val title = getString(R.string.reset_card_dialog_title)
        val message = getString(R.string.reset_card_dialog_message)
        dialog.setArgs(title, message)
        val confirm = Runnable {
            Timber.i("CardBrowser:: ResetProgress button pressed")
            resetProgressNoConfirm(selectedCardIds)
        }
        dialog.setConfirm(confirm)
        showDialogFragment(dialog)
    }

    @VisibleForTesting
    fun resetProgressNoConfirm(cardIds: List<Long>) {
        launchCatchingTask {
            resetCards(cardIds)
        }
    }

    @VisibleForTesting
    fun repositionCardsNoValidation(position: Int) = launchCatchingTask {
        val count = withProgress { viewModel.repositionSelectedRows(position) }
        showSnackbar(
            resources.getQuantityString(
                R.plurals.reposition_card_dialog_acknowledge,
                count,
                count
            ),
            Snackbar.LENGTH_SHORT
        )
    }

    private fun onPreview() {
        val intentData = viewModel.previewIntentData
        onPreviewCardsActivityResult.launch(getPreviewIntent(intentData.index, intentData.cardList))
    }

    private fun getPreviewIntent(index: Int, selectedCardIds: LongArray): Intent {
        return if (sharedPrefs().getBoolean("new_previewer", false)) {
            Previewer2Destination(index, selectedCardIds).toIntent(this)
        } else {
            PreviewDestination(index, selectedCardIds).toIntent(this)
        }
    }

    private fun rescheduleSelectedCards() {
        if (!viewModel.hasSelectedAnyRows()) {
            Timber.i("Attempted reschedule - no cards selected")
            return
        }
        val rescheduleDialog: RescheduleDialog = selectedCardIds.run {
            val consumer = Consumer { newDays: Int -> rescheduleWithoutValidation(this, newDays) }
            if (size == 1) {
                rescheduleSingleCard(resources, getColUnsafe.getCard(this[0]), consumer)
            } else {
                rescheduleMultipleCards(resources, consumer, size)
            }
        }
        showDialogFragment(rescheduleDialog)
    }

    @VisibleForTesting
    fun rescheduleWithoutValidation(selectedCardIds: List<CardId>, newDays: Int) {
        launchCatchingTask {
            rescheduleCards(selectedCardIds, newDays)
        }
    }

    @KotlinCleanup("DeckSelectionListener is almost certainly a bug - deck!!")
    fun getChangeDeckDialog(selectableDecks: List<SelectableDeck>?): DeckSelectionDialog {
        val dialog = newInstance(
            getString(R.string.move_all_to_deck),
            null,
            false,
            selectableDecks!!
        )
        // Add change deck argument so the dialog can be dismissed
        // after activity recreation, since the selected cards will be gone with it
        dialog.requireArguments().putBoolean(CHANGE_DECK_KEY, true)
        dialog.deckSelectionListener = DeckSelectionListener { deck: SelectableDeck? -> moveSelectedCardsToDeck(deck!!.deckId) }
        return dialog
    }

    private fun showChangeDeckDialog() = launchCatchingTask {
        if (!viewModel.hasSelectedAnyRows()) {
            Timber.i("Not showing Change Deck - No Cards")
            return@launchCatchingTask
        }
        val selectableDecks = getValidDecksForChangeDeck()
            .map { d -> SelectableDeck(d) }
        val dialog = getChangeDeckDialog(selectableDecks)
        showDialogFragment(dialog)
    }

    @get:VisibleForTesting
    val addNoteIntent: Intent
        get() {
            val intent = Intent(this@CardBrowser, NoteEditor::class.java)
            intent.putExtra(NoteEditor.EXTRA_CALLER, NoteEditor.CALLER_CARDBROWSER_ADD)
            if (viewModel.lastDeckId?.let { id -> id > 0 } == true) {
                intent.putExtra(NoteEditor.EXTRA_DID, viewModel.lastDeckId)
            }
            intent.putExtra(NoteEditor.EXTRA_TEXT_FROM_SEARCH_VIEW, mSearchTerms)
            return intent
        }

    private fun addNoteFromCardBrowser() {
        onAddNoteActivityResult.launch(addNoteIntent)
    }

    private val reviewerCardId: CardId
        get() = intent.getLongExtra("currentCard", -1)

    private fun showEditTagsDialog() {
        if (selectedCardIds.isEmpty()) {
            Timber.d("showEditTagsDialog: called with empty selection")
        }
        val allTags = getColUnsafe.tags.all()
        val selectedNotes = selectedCardIds
            .map { cardId: CardId? -> getColUnsafe.getCard(cardId!!).note(getColUnsafe) }
            .distinct()
        val checkedTags = selectedNotes
            .flatMap { note: Note -> note.tags }
        if (selectedNotes.size == 1) {
            Timber.d("showEditTagsDialog: edit tags for one note")
            mTagsDialogListenerAction = TagsDialogListenerAction.EDIT_TAGS
            val dialog = mTagsDialogFactory.newTagsDialog().withArguments(TagsDialog.DialogType.EDIT_TAGS, checkedTags, allTags)
            showDialogFragment(dialog)
            return
        }
        val uncheckedTags = selectedNotes
            .flatMap { note: Note ->
                val noteTags: List<String?> = note.tags
                allTags.filter { t: String? -> !noteTags.contains(t) }
            }
        Timber.d("showEditTagsDialog: edit tags for multiple note")
        mTagsDialogListenerAction = TagsDialogListenerAction.EDIT_TAGS
        val dialog = mTagsDialogFactory.newTagsDialog().withArguments(
            TagsDialog.DialogType.EDIT_TAGS,
            checkedTags,
            uncheckedTags,
            allTags
        )
        showDialogFragment(dialog)
    }

    private fun showFilterByTagsDialog() {
        mTagsDialogListenerAction = TagsDialogListenerAction.FILTER
        val dialog = mTagsDialogFactory.newTagsDialog().withArguments(
            TagsDialog.DialogType.FILTER_BY_TAG,
            ArrayList(0),
            getColUnsafe.tags.all()
        )
        showDialogFragment(dialog)
    }

    private fun showOptionsDialog() {
        val dialog = BrowserOptionsDialog(viewModel.cardsOrNotes, viewModel.isTruncated)
        dialog.show(supportFragmentManager, "browserOptionsDialog")
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        // Save current search terms
        savedInstanceState.putString("mSearchTerms", mSearchTerms)
        savedInstanceState.putLong("mOldCardId", mOldCardId)
        savedInstanceState.putInt("mOldCardTopOffset", mOldCardTopOffset)
        savedInstanceState.putBoolean("mShouldRestoreScroll", mShouldRestoreScroll)
        savedInstanceState.putBoolean("mPostAutoScroll", mPostAutoScroll)
        savedInstanceState.putInt("mLastSelectedPosition", mLastSelectedPosition)
        mExportingDelegate.onSaveInstanceState(savedInstanceState)
        super.onSaveInstanceState(savedInstanceState)
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        mSearchTerms = savedInstanceState.getString("mSearchTerms", "")
        mOldCardId = savedInstanceState.getLong("mOldCardId")
        mOldCardTopOffset = savedInstanceState.getInt("mOldCardTopOffset")
        mShouldRestoreScroll = savedInstanceState.getBoolean("mShouldRestoreScroll")
        mPostAutoScroll = savedInstanceState.getBoolean("mPostAutoScroll")
        mLastSelectedPosition = savedInstanceState.getInt("mLastSelectedPosition")
        searchCards()
    }

    private fun invalidate() {
        renderBrowserQAJob?.cancel()
    }

    private fun forceRefreshSearch(useSearchTextValue: Boolean = false) {
        if (useSearchTextValue && mSearchView != null) {
            mSearchTerms = mSearchView!!.query.toString()
        }
        searchCards()
    }

    @RustCleanup("remove card cache; switch to RecyclerView and browserRowForId (#11889)")
    @VisibleForTesting
    fun searchCards() {
        if (!viewModel.initCompleted) {
            Timber.d("!initCompleted, not searching")
            return
        }
        // cancel the previous search & render tasks if still running
        invalidate()
        if ("" != mSearchTerms && mSearchView != null) {
            mSearchView!!.setQuery(mSearchTerms, false)
            mSearchItem!!.expandActionView()
        }
        val searchText: String? = if (mSearchTerms.contains("deck:")) {
            "($mSearchTerms)"
        } else {
            if ("" != mSearchTerms) "${viewModel.restrictOnDeck}($mSearchTerms)" else viewModel.restrictOnDeck
        }
        // clear the existing card list
        mCards.reset()
        cardsAdapter.notifyDataSetChanged()
        val query = searchText!!
        val order = viewModel.order.toSortOrder()
        launchCatchingTask {
            Timber.d("performing search")
            val cards = withProgress { searchForCards(query, order, viewModel.cardsOrNotes) }
            Timber.d("Search returned %d cards", cards.size)
            // Render the first few items
            for (i in 0 until Math.min(numCardsToRender(), cards.size)) {
                cards[i].load(false, viewModel.column1Index, viewModel.column2Index)
            }
            redrawAfterSearch(cards)
        }
    }

    fun redrawAfterSearch(cards: MutableList<CardCache>) {
        mCards.replaceWith(cards)
        Timber.i("CardBrowser:: Completed searchCards() Successfully")
        updateList()
        /*check whether mSearchView is initialized as it is lateinit property.*/
        if (mSearchView == null || mSearchView!!.isIconified) {
            restoreScrollPositionIfRequested()
            return
        }
        if (hasSelectedAllDecks()) {
            showSnackbar(subtitleText, Snackbar.LENGTH_SHORT)
        } else {
            // If we haven't selected all decks, allow the user the option to search all decks.
            val message = if (viewModel.rowCount == 0) {
                getString(R.string.card_browser_no_cards_in_deck, selectedDeckNameForUi)
            } else {
                subtitleText
            }
            showSnackbar(message, Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.card_browser_search_all_decks) { searchAllDecks() }
            }
        }
        restoreScrollPositionIfRequested()
        updatePreviewMenuItem()
    }

    /**
     * Restores the scroll position of the browser when requested (for example after editing a card)
     */
    @NeedsTest("Issue 14220: Ensure this is called if mSearchView == null. Use Espresso to test")
    private fun restoreScrollPositionIfRequested() {
        if (!mShouldRestoreScroll) {
            Timber.d("Not restoring search position")
            return
        }
        mShouldRestoreScroll = false
        val card = viewModel.findCardById(mOldCardId) ?: return
        Timber.d("Restoring scroll position after search")
        autoScrollTo(card.position)
    }

    @VisibleForTesting
    protected open fun numCardsToRender(): Int {
        return ceil(
            (
                cardsListView.height /
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
                ).toDouble()
        ).toInt() + 5
    }

    private fun updateList() {
        if (colIsOpenUnsafe()) {
            cardsAdapter.notifyDataSetChanged()
            deckSpinnerSelection!!.notifyDataSetChanged()
            onSelectionChanged()
            updatePreviewMenuItem()
        }
    }

    /**
     * @return text to be used in the subtitle of the drop-down deck selector
     */
    override val subtitleText: String
        get() {
            val count = viewModel.rowCount

            @androidx.annotation.StringRes val subtitleId = if (viewModel.cardsOrNotes == CARDS) {
                R.plurals.card_browser_subtitle
            } else {
                R.plurals.card_browser_subtitle_notes_mode
            }
            return resources.getQuantityString(subtitleId, count, count)
        }

    // convenience method for updateCardsInList(...)
    private fun updateCardInList(card: Card) {
        val cards: MutableList<Card> = java.util.ArrayList(1)
        cards.add(card)
        updateCardsInList(cards)
    }

    /** Returns the decks which are valid targets for "Change Deck"  */
    suspend fun getValidDecksForChangeDeck(): List<DeckNameId> =
        deckSpinnerSelection!!.computeDropDownDecks(includeFiltered = false)

    @RustCleanup("this isn't how Desktop Anki does it")
    override fun onSelectedTags(selectedTags: List<String>, indeterminateTags: List<String>, stateFilter: CardStateFilter) {
        when (mTagsDialogListenerAction) {
            TagsDialogListenerAction.FILTER -> filterByTags(selectedTags, stateFilter)
            TagsDialogListenerAction.EDIT_TAGS -> launchCatchingTask {
                editSelectedCardsTags(selectedTags, indeterminateTags)
            }
            else -> {}
        }
    }

    /**
     * Updates the tags of selected/checked notes and saves them to the disk
     * @param selectedTags list of checked tags
     * @param indeterminateTags a list of tags which can checked or unchecked, should be ignored if not expected
     * For more info on [selectedTags] and [indeterminateTags] see [com.ichi2.anki.dialogs.tags.TagsDialogListener.onSelectedTags]
     */
    private suspend fun editSelectedCardsTags(selectedTags: List<String>, indeterminateTags: List<String>) = withProgress {
        undoableOp {
            val selectedNotes = selectedCardIds
                .map { cardId -> getCard(cardId).note() }
                .distinct()
                .onEach { note ->
                    val previousTags: List<String> = note.tags
                    val updatedTags = getUpdatedTags(previousTags, selectedTags, indeterminateTags)
                    note.setTagsFromStr(tags.join(updatedTags))
                }
            updateNotes(selectedNotes)
        }
    }

    private fun filterByTags(selectedTags: List<String>, cardState: CardStateFilter) =
        launchCatchingTask {
            viewModel.filterByTags(selectedTags, cardState)
        }

    /** Updates search terms to only show cards with selected flag.  */
    @VisibleForTesting
    fun filterByFlag(flag: Flag) = launchCatchingTask { viewModel.setFlagFilter(flag) }

    /**
     * Loads/Reloads (Updates the Q, A & etc) of cards in the [cards] list
     * @param cards Cards that were changed
     */
    private fun updateCardsInList(cards: List<Card>) {
        val idToPos = viewModel.cardIdToPositionMap
        // TODO: Inefficient
        cards
            .mapNotNull { c -> idToPos[c.id] }
            .filterNot { pos -> pos >= viewModel.rowCount }
            .map { pos -> viewModel.getRowAtPosition(pos) }
            .forEach { it.load(true, viewModel.column1Index, viewModel.column2Index) }
        updateList()
    }

    private suspend fun saveEditedCard() {
        Timber.d("CardBrowser - saveEditedCard()")
        val card = cardBrowserCard!!
        withProgress {
            undoableOp {
                updateNote(card.note())
            }
        }
        updateCardInList(card)
    }

    /**
     * Removes cards from view. Doesn't delete them in model (database).
     * @param reorderCards Whether to rearrange the positions of checked items (DEFECT: Currently deselects all)
     */
    private fun removeNotesView(cardsIds: List<Long>, reorderCards: Boolean) {
        val idToPos = viewModel.cardIdToPositionMap
        val idToRemove = cardsIds.filter { cId -> idToPos.containsKey(cId) }
        mReloadRequired = mReloadRequired || cardsIds.contains(reviewerCardId)
        val newMCards: MutableList<CardCache> = mCards
            .filterNot { c -> idToRemove.contains(c.id) }
            .mapIndexed { i, c -> CardCache(c, i) }
            .toMutableList()
        mCards.replaceWith(newMCards)
        if (reorderCards) {
            // Suboptimal from a UX perspective, we should reorder
            // but this is only hit on a rare sad path and we'd need to rejig the data structures to allow an efficient
            // search
            Timber.w("Removing current selection due to unexpected removal of cards")
            viewModel.selectNone()
        }
        updateList()
    }

    private fun suspendCards() = launchCatchingTask { withProgress { viewModel.suspendCards() } }

    private fun showUndoSnackbar(message: CharSequence) {
        showSnackbar(message, Snackbar.LENGTH_LONG) {
            setAction(R.string.undo) { launchCatchingTask { undoAndShowSnackbar() } }
            mUndoSnackbar = this
        }
    }

    private fun refreshAfterUndo() {
        hideProgressBar()
        // reload whole view
        forceRefreshSearch()
        viewModel.endMultiSelectMode()
        cardsAdapter.notifyDataSetChanged()
        updatePreviewMenuItem()
        invalidateOptionsMenu() // maybe the availability of undo changed
    }
    private fun saveScrollingState(position: Int) {
        mOldCardId = viewModel.getCardIdAtPosition(position)
        mOldCardTopOffset = calculateTopOffset(position)
    }

    private fun autoScrollTo(newPosition: Int) {
        cardsListView.setSelectionFromTop(newPosition, mOldCardTopOffset)
        mPostAutoScroll = true
    }

    private fun calculateTopOffset(cardPosition: Int): Int {
        val firstVisiblePosition = cardsListView.firstVisiblePosition
        val v = cardsListView.getChildAt(cardPosition - firstVisiblePosition)
        return v?.top ?: 0
    }

    fun hasSelectedAllDecks(): Boolean = viewModel.lastDeckId == ALL_DECKS_ID

    fun searchAllDecks() = launchCatchingTask {
        // all we need to do is select all decks
        viewModel.setDeckId(ALL_DECKS_ID)
    }

    /**
     * Returns the current deck name, "All Decks" if all decks are selected, or "Unknown"
     * Do not use this for any business logic, as this will return inconsistent data
     * with the collection.
     */
    val selectedDeckNameForUi: String
        get() = try {
            when (val deckId = viewModel.lastDeckId) {
                null -> getString(R.string.card_browser_unknown_deck_name)
                ALL_DECKS_ID -> getString(R.string.card_browser_all_decks)
                else -> getColUnsafe.decks.name(deckId)
            }
        } catch (e: Exception) {
            Timber.w(e, "Unable to get selected deck name")
            getString(R.string.card_browser_unknown_deck_name)
        }

    private fun onPostExecuteRenderBrowserQA(result: Pair<List<CardCache>, List<Long>>) {
        val cardsIdsToHide = result.second
        try {
            if (cardsIdsToHide.isNotEmpty()) {
                Timber.i("Removing %d invalid cards from view", cardsIdsToHide.size)
                removeNotesView(cardsIdsToHide, true)
            }
        } catch (e: Exception) {
            Timber.e(e, "failed to hide cards")
        }
        hideProgressBar() // Some places progressbar is launched explicitly, so hide it
        cardsAdapter.notifyDataSetChanged()
        Timber.d("Completed doInBackgroundRenderBrowserQA Successfully")
    }

    private fun closeCardBrowser(result: Int, data: Intent? = null) {
        // Set result and finish
        setResult(result, data)
        finish()
    }

    /**
     * Render the second column whenever the user stops scrolling
     */
    @VisibleForTesting
    inner class RenderOnScroll : AbsListView.OnScrollListener {
        override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
            // Show the progress bar if scrolling to given position requires rendering of the question / answer
            val lastVisibleItem = firstVisibleItem + visibleItemCount - 1
            // List is never cleared, only reset to a new list. So it's safe here.
            val size = viewModel.rowCount
            if (size > 0 && visibleItemCount <= 0) {
                // According to Mike, there used to be 5 to 10 report by hour on the beta version. All with
                // > com.ichi2.anki.exception.ManuallyReportedException: Useless onScroll call, with size 0 firstVisibleItem 0,
                // > lastVisibleItem 0 and visibleItemCount 0.

                // This change ensure that we log more specifically case where #8821 could have occurred. That is, there are cards but we
                // are asked to display nothing.

                // Note that this is not a bug. The fact that `visibleItemCount` is equal to 0 is actually authorized by the method we
                // override and mentioned in the javadoc. It perfectly makes sens to get this order, since it can be used to know that we
                // can delete some elements from the cache for example, since nothing is displayed.

                // It would be interesting to know how often it occurs, but it is not a bug.
                CrashReportService.sendExceptionReport("CardBrowser Scroll Issue 8821", "In a search result of $size cards, with totalItemCount = $totalItemCount, somehow we got $visibleItemCount elements to display.")
            }
            // In all of those cases, there is nothing to do:
            if (size <= 0 || firstVisibleItem >= size || lastVisibleItem >= size || visibleItemCount <= 0) {
                return
            }
            val firstLoaded = viewModel.getRowAtPosition(firstVisibleItem).isLoaded
            // Note: max value of lastVisibleItem is totalItemCount, so need to subtract 1
            val lastLoaded = viewModel.getRowAtPosition(lastVisibleItem).isLoaded
            if (!firstLoaded || !lastLoaded) {
                if (!mPostAutoScroll) {
                    showProgressBar()
                }
                // Also start rendering the items on the screen every 300ms while scrolling
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - mLastRenderStart > 300 || lastVisibleItem + 1 >= totalItemCount) {
                    mLastRenderStart = currentTime
                    renderBrowserQAJob?.cancel()
                    launchCatchingTask { renderBrowserQAParams(firstVisibleItem, visibleItemCount, viewModel.cards.toList()) }
                }
            }
        }

        override fun onScrollStateChanged(listView: AbsListView, scrollState: Int) {
            // TODO: Try change to RecyclerView as currently gets stuck a lot when using scrollbar on right of ListView
            // Start rendering the question & answer every time the user stops scrolling
            if (mPostAutoScroll) {
                mPostAutoScroll = false
            }
            if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                val startIdx = listView.firstVisiblePosition
                val numVisible = listView.lastVisiblePosition - startIdx
                launchCatchingTask { renderBrowserQAParams(startIdx - 5, 2 * numVisible + 5, viewModel.cards.toList()) }
            }
        }
    }

    // TODO: Improve progress bar handling in places where this function is used
    protected suspend fun renderBrowserQAParams(firstVisibleItem: Int, visibleItemCount: Int, cards: List<CardCache>) {
        Timber.d("Starting Q&A background rendering")
        val result = renderBrowserQA(
            cards,
            firstVisibleItem,
            visibleItemCount,
            viewModel.column1Index,
            viewModel.column2Index
        ) {
            // Note: This is called every time a card is rendered.
            // It blocks the long-click callback while the task is running, so usage of the task should be minimized
            cardsAdapter.notifyDataSetChanged()
        }
        onPostExecuteRenderBrowserQA(result)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    inner class MultiColumnListAdapter(
        context: Context?,
        private val resource: Int,
        private var fromKeys: Array<CardBrowserColumn>,
        private val toIds: IntArray,
        private val fontSizeScalePcent: Int
    ) : BaseAdapter() {
        private var mOriginalTextSize = -1.0f
        private val mInflater: LayoutInflater
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // Get the main container view if it doesn't already exist, and call bindView
            val v: View
            if (convertView == null) {
                v = mInflater.inflate(resource, parent, false)
                val count = toIds.size
                val columns = arrayOfNulls<View>(count)
                for (i in 0 until count) {
                    columns[i] = v.findViewById(toIds[i])
                }
                v.tag = columns
            } else {
                v = convertView
            }
            bindView(position, v)
            return v
        }

        @Suppress("UNCHECKED_CAST")
        @KotlinCleanup("Unchecked cast")
        private fun bindView(position: Int, v: View) {
            // Draw the content in the columns
            val card = getItem(position)
            (v.tag as Array<*>)
                .forEachIndexed { i, column ->
                    setFont(column as TextView) // set font for column
                    column.text = card.getColumnHeaderText(fromKeys[i]) // set text for column
                }
            // set card's background color
            val backgroundColor: Int = MaterialColors.getColor(this@CardBrowser, card.color, 0)
            v.setBackgroundColor(backgroundColor)
            // setup checkbox to change color in multi-select mode
            val checkBox = v.findViewById<CheckBox>(R.id.card_checkbox)
            // if in multi-select mode, be sure to show the checkboxes
            if (viewModel.isInMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = viewModel.selectedRows.contains(card)
                // this prevents checkboxes from showing an animation from selected -> unselected when
                // checkbox was selected, then selection mode was ended and now restarted
                checkBox.jumpDrawablesToCurrentState()
            } else {
                checkBox.isChecked = false
                checkBox.visibility = View.GONE
            }
            // change bg color on check changed
            checkBox.setOnClickListener { viewModel.toggleRowSelectionAtPosition(position) }
            val column1 = v.findViewById<FixedTextView>(R.id.card_sfld)
            val column2 = v.findViewById<FixedTextView>(R.id.card_column2)

            if (viewModel.isTruncated) {
                column1.maxLines = LINES_VISIBLE_WHEN_COLLAPSED
                column2.maxLines = LINES_VISIBLE_WHEN_COLLAPSED
                column1.ellipsize = TextUtils.TruncateAt.END
                column2.ellipsize = TextUtils.TruncateAt.END
            } else {
                column1.maxLines = Integer.MAX_VALUE
                column2.maxLines = Integer.MAX_VALUE
            }
        }

        private fun setFont(v: TextView) {
            // Set the font and font size for a TextView v
            val currentSize = v.textSize
            if (mOriginalTextSize < 0) {
                mOriginalTextSize = v.textSize
            }
            // do nothing when pref is 100% and apply scaling only once
            if (fontSizeScalePcent != 100 && abs(mOriginalTextSize - currentSize) < 0.1) {
                // getTextSize returns value in absolute PX so use that in the setter
                v.setTextSize(TypedValue.COMPLEX_UNIT_PX, mOriginalTextSize * (fontSizeScalePcent / 100.0f))
            }
        }

        private var fromMapping: Array<CardBrowserColumn>
            get() = fromKeys
            set(from) {
                fromKeys = from
                notifyDataSetChanged()
            }

        fun updateMapping(fn: (Array<CardBrowserColumn>) -> Unit) {
            val fromMap = fromMapping
            fn(fromMap)
            // this doesn't need to be run on the UI thread: this calls notifyDataSetChanged()
            fromMapping = fromMap
        }

        override fun getCount(): Int {
            return viewModel.rowCount
        }

        override fun getItem(position: Int): CardCache = viewModel.getRowAtPosition(position)

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        init {
            mInflater = LayoutInflater.from(context)
        }
    }

    fun onSelectionChanged() {
        Timber.d("onSelectionChanged()")
        try {
            mActionBarTitle.text = String.format(LanguageUtil.getLocaleCompat(resources), "%d", viewModel.selectedRowCount())

            // If we're not in mutliselect, we can select cards if there are cards to select
            if (!viewModel.isInMultiSelectMode) {
                mActionBarMenu?.findItem(R.id.action_select_all)?.apply {
                    isVisible = viewModel.rowCount != 0
                }
                return
            }
            updateMultiselectMenu()
        } finally {
            if (colIsOpenUnsafe()) {
                cardsAdapter.notifyDataSetChanged()
            }
        }
    }

    /**
     * Implementation of `by viewModels()` for use in [onCreate]
     *
     * @see showedActivityFailedScreen - we may not have AnkiDroidApp.instance and therefore can't
     * create the ViewModel
     */
    private fun createViewModel() = ViewModelProvider(
        viewModelStore,
        CardBrowserViewModel.factory(AnkiDroidApp.instance.sharedPrefsLastDeckIdRepository),
        defaultViewModelCreationExtras
    )[CardBrowserViewModel::class.java]

    // This could be better: use a wrapper class PositionAware<T> to store the position so it's
    // no longer a responsibility of CardCache and we can guarantee it's consistent just by using this collection
    /** A position-aware collection to ensure consistency between the position of items and the collection  */
    class CardCollection<T : PositionAware?> : Iterable<T> {
        var wrapped: MutableList<T> = ArrayList(0)
            private set
        fun size(): Int {
            return wrapped.size
        }

        operator fun get(index: Int): T {
            return wrapped[index]
        }

        fun reset() {
            wrapped = ArrayList(0)
        }

        fun replaceWith(value: MutableList<T>) {
            wrapped = value
        }

        fun reverse() {
            wrapped.reverse()
            wrapped.forEachIndexed { pos, card -> card!!.position = pos }
        }

        override fun iterator(): MutableIterator<T> {
            return wrapped.iterator()
        }

        fun clear() {
            wrapped.clear()
        }
    }

    @VisibleForTesting
    interface PositionAware {
        var position: Int
    }

    class CardCache : Card.Cache, PositionAware {
        var isLoaded = false
            private set
        private var mQa: Pair<String, String>? = null
        override var position: Int

        private val inCardMode: Boolean
        constructor(id: Long, col: Collection, position: Int, cardsOrNotes: CardsOrNotes) : super(col, id) {
            this.position = position
            this.inCardMode = cardsOrNotes == CARDS
        }

        constructor(cache: CardCache, position: Int) : super(cache) {
            isLoaded = cache.isLoaded
            mQa = cache.mQa
            this.position = position
            this.inCardMode = cache.inCardMode
        }

        /** clear all values except ID. */
        override fun reload() {
            super.reload()
            isLoaded = false
            mQa = null
        }

        /**
         * Get the background color of items in the card list based on the Card
         * @return index into TypedArray specifying the background color
         */
        val color: Int
            get() {
                return when (card.userFlag()) {
                    1 -> R.attr.flagRed
                    2 -> R.attr.flagOrange
                    3 -> R.attr.flagGreen
                    4 -> R.attr.flagBlue
                    5 -> R.attr.flagPink
                    6 -> R.attr.flagTurquoise
                    7 -> R.attr.flagPurple
                    else -> {
                        if (isMarked(col, card.note(col))) {
                            R.attr.markedColor
                        } else if (card.queue == Consts.QUEUE_TYPE_SUSPENDED) {
                            R.attr.suspendedColor
                        } else {
                            android.R.attr.colorBackground
                        }
                    }
                }
            }

        fun getColumnHeaderText(key: CardBrowserColumn?): String? {
            return when (key) {
                CardBrowserColumn.FLAGS -> Integer.valueOf(card.userFlag()).toString()
                CardBrowserColumn.SUSPENDED -> if (card.queue == Consts.QUEUE_TYPE_SUSPENDED) "True" else "False"
                CardBrowserColumn.MARKED -> if (isMarked(col, card.note(col))) "marked" else null
                CardBrowserColumn.SFLD -> card.note(col).sFld(col)
                CardBrowserColumn.DECK -> col.decks.name(card.did)
                CardBrowserColumn.TAGS -> card.note(col).stringTags(col)
                CardBrowserColumn.CARD -> if (inCardMode) card.template(col).optString("name") else "${card.note(col).numberOfCards(col)}"
                CardBrowserColumn.DUE -> card.dueString(col)
                CardBrowserColumn.EASE -> if (inCardMode) getEaseForCards() else getAvgEaseForNotes()
                CardBrowserColumn.CHANGED -> LanguageUtil.getShortDateFormatFromS(if (inCardMode) card.mod else card.note(col).mod.toLong())
                CardBrowserColumn.CREATED -> LanguageUtil.getShortDateFormatFromMs(card.nid)
                CardBrowserColumn.EDITED -> LanguageUtil.getShortDateFormatFromS(card.note(col).mod)
                CardBrowserColumn.INTERVAL -> if (inCardMode) queryIntervalForCards() else queryAvgIntervalForNotes()
                CardBrowserColumn.LAPSES -> (if (inCardMode) card.lapses else card.totalLapsesOfNote(col)).toString()
                CardBrowserColumn.NOTE_TYPE -> card.model(col).optString("name")
                CardBrowserColumn.REVIEWS -> if (inCardMode) card.reps.toString() else card.totalReviewsForNote(col).toString()
                CardBrowserColumn.QUESTION -> {
                    updateSearchItemQA()
                    mQa!!.first
                }
                CardBrowserColumn.ANSWER -> {
                    updateSearchItemQA()
                    mQa!!.second
                }
                else -> null
            }
        }

        private fun getEaseForCards(): String {
            return if (card.type == Consts.CARD_TYPE_NEW) {
                AnkiDroidApp.instance.getString(R.string.card_browser_interval_new_card)
            } else {
                "${card.factor / 10}%"
            }
        }

        private fun getAvgEaseForNotes(): String {
            val avgEase = card.avgEaseOfNote(col)

            return if (avgEase == null) {
                AnkiDroidApp.instance.getString(R.string.card_browser_interval_new_card)
            } else {
                "$avgEase%"
            }
        }

        private fun queryIntervalForCards(): String {
            return when (card.type) {
                Consts.CARD_TYPE_NEW -> AnkiDroidApp.instance.getString(R.string.card_browser_interval_new_card)
                Consts.CARD_TYPE_LRN -> AnkiDroidApp.instance.getString(R.string.card_browser_interval_learning_card)
                else -> roundedTimeSpanUnformatted(AnkiDroidApp.instance, card.ivl * SECONDS_PER_DAY)
            }
        }

        private fun queryAvgIntervalForNotes(): String {
            val avgInterval = card.avgIntervalOfNote(col)

            return if (avgInterval == null) {
                "" // upstream does not display interval for notes with new or learning cards
            } else {
                roundedTimeSpanUnformatted(AnkiDroidApp.instance, avgInterval * SECONDS_PER_DAY)
            }
        }

        /** pre compute the note and question/answer.  It can safely
         * be called twice without doing extra work.  */
        fun load(reload: Boolean, column1Index: Int, column2Index: Int) {
            if (reload) {
                reload()
            }
            card.note(col)
            // First column can not be the answer. If it were to change, this code should also be changed.
            if (COLUMN1_KEYS[column1Index] == CardBrowserColumn.QUESTION || arrayOf(CardBrowserColumn.QUESTION, CardBrowserColumn.ANSWER).contains(COLUMN2_KEYS[column2Index])) {
                updateSearchItemQA()
            }
            isLoaded = true
        }

        /**
         * Reload question and answer. Use browser format. If it's empty
         * uses non-browser format. If answer starts by question, remove
         * question.
         */
        private fun updateSearchItemQA() {
            if (mQa != null) {
                return
            }
            // render question and answer
            val qa = card.renderOutput(col, reload = true, browser = true)
            // Render full question / answer if the bafmt (i.e. "browser appearance") setting forced blank result
            if (qa.questionText.isEmpty() || qa.answerText.isEmpty()) {
                val (questionText, answerText) = card.renderOutput(
                    col,
                    reload = true,
                    browser = false
                )
                if (qa.questionText.isEmpty()) {
                    qa.questionText = questionText
                }
                if (qa.answerText.isEmpty()) {
                    qa.answerText = answerText
                }
            }
            // update the original hash map to include rendered question & answer
            var q = qa.questionText
            var a = qa.answerText
            // remove the question from the start of the answer if it exists
            if (a.startsWith(q)) {
                a = a.substring(q.length)
            }
            a = formatQA(a, qa, AnkiDroidApp.instance)
            q = formatQA(q, qa, AnkiDroidApp.instance)
            mQa = Pair(q, a)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null) {
                return false
            }
            return if (javaClass != other.javaClass) {
                false
            } else {
                id == (other as CardCache).id
            }
        }

        override fun hashCode(): Int {
            return java.lang.Long.valueOf(id).hashCode()
        }
    }

    /**
     * Show/dismiss dialog when sd card is ejected/remounted (collection is saved by SdCardReceiver)
     */
    private fun registerExternalStorageListener() {
        if (mUnmountReceiver == null) {
            mUnmountReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == SdCardReceiver.MEDIA_EJECT) {
                        finish()
                    }
                }
            }
            val iFilter = IntentFilter()
            iFilter.addAction(SdCardReceiver.MEDIA_EJECT)
            registerReceiver(mUnmountReceiver, iFilter)
        }
    }

    /**
     * The views expand / contract when switching between multi-select mode so we manually
     * adjust so that the vertical position of the given view is maintained
     */
    private fun recenterListView(view: View) {
        val position = cardsListView.getPositionForView(view)
        // Get the current vertical position of the top of the selected view
        val top = view.top
        // Post to event queue with some delay to give time for the UI to update the layout
        postDelayedOnNewHandler({
            // Scroll to the same vertical position before the layout was changed
            cardsListView.setSelectionFromTop(position, top)
        }, 10)
    }

    @get:VisibleForTesting(otherwise = VisibleForTesting.NONE)
    val isShowingSelectAll: Boolean
        get() = mActionBarMenu?.findItem(R.id.action_select_all)?.isVisible == true

    @get:VisibleForTesting(otherwise = VisibleForTesting.NONE)
    val isShowingSelectNone: Boolean
        get() = mActionBarMenu?.findItem(R.id.action_select_none)?.isVisible == true

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun clearCardData(position: Int) {
        viewModel.getRowAtPosition(position).reload()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    suspend fun rerenderAllCards() {
        renderBrowserQAParams(0, viewModel.rowCount - 1, viewModel.cards.toList())
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun filterByTag(vararg tags: String) {
        mTagsDialogListenerAction = TagsDialogListenerAction.FILTER
        onSelectedTags(tags.toList(), emptyList(), CardStateFilter.ALL_CARDS)
        filterByTags(tags.toList(), CardStateFilter.ALL_CARDS)
    }

    @VisibleForTesting
    fun searchCards(searchQuery: String) {
        mSearchTerms = searchQuery
        searchCards()
    }

    override fun opExecuted(changes: OpChanges, handler: Any?) {
        if (handler === this || handler === viewModel) {
            return
        }

        if ((
            changes.browserSidebar ||
                changes.browserTable ||
                changes.noteText ||
                changes.card
            )
        ) {
            refreshAfterUndo()
        }
    }

    companion object {
        var cardBrowserCard: Card? = null

        /**
         * Argument key to add on change deck dialog,
         * so it can be dismissed on activity recreation,
         * since the cards are unselected when this happens
         */
        private const val CHANGE_DECK_KEY = "CHANGE_DECK"
        private const val DEFAULT_FONT_SIZE_RATIO = 100

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        const val LINES_VISIBLE_WHEN_COLLAPSED = 3

        // Values related to persistent state data
        private const val ALL_DECKS_ID = 0L
        const val CARD_NOT_AVAILABLE = -1

        fun clearLastDeckId() = SharedPreferencesLastDeckIdRepository.clearLastDeckId()

        @CheckResult
        private fun formatQA(
            text: String,
            qa: TemplateManager.TemplateRenderContext.TemplateRenderOutput,
            context: Context
        ): String {
            val showFilenames =
                context.sharedPrefs().getBoolean("card_browser_show_media_filenames", false)
            return formatQAInternal(text, qa, showFilenames)
        }

        /**
         * @param txt The text to strip HTML, comments, tags and media from
         * @param showFileNames Whether [sound:foo.mp3] should be rendered as " foo.mp3 " or  " "
         * @return The formatted string
         */
        @VisibleForTesting
        @CheckResult
        fun formatQAInternal(
            txt: String,
            qa: TemplateManager.TemplateRenderContext.TemplateRenderOutput,
            showFileNames: Boolean
        ): String {
            /* Strips all formatting from the string txt for use in displaying question/answer in browser */
            var s = txt
            s = s.replace("<!--.*?-->".toRegex(), "")
            s = s.replace("<br>", " ")
            s = s.replace("<br />", " ")
            s = s.replace("<div>", " ")
            s = s.replace("\n", " ")
            // we use " " as often users won't leave a space between the '[sound:] tag
            // and continuation of the content
            s = if (showFileNames) Sound.replaceWithFileNames(s, qa) else stripAvRefs(s, " ")
            s = s.replace("\\[\\[type:[^]]+]]".toRegex(), "")
            s = if (showFileNames) Utils.stripHTMLMedia(s) else Utils.stripHTMLMedia(s, " ")
            s = s.trim { it <= ' ' }
            return s
        }
    }
}

suspend fun searchForCards(
    query: String,
    order: SortOrder,
    cardsOrNotes: CardsOrNotes
): MutableList<CardBrowser.CardCache> {
    return withCol {
        (if (cardsOrNotes == CARDS) findCards(query, order) else findOneCardByNote(query, order)).asSequence()
            .toCardCache(cardsOrNotes)
            .toMutableList()
    }
}

context (Collection)
private fun Sequence<CardId>.toCardCache(isInCardMode: CardsOrNotes): Sequence<CardBrowser.CardCache> {
    return this.mapIndexed { idx, cid -> CardBrowser.CardCache(cid, this@Collection, idx, isInCardMode) }
}

class Previewer2Destination(val currentIndex: Int, val selectedCardIds: LongArray)

@CheckResult
fun Previewer2Destination.toIntent(context: Context) =
    PreviewerFragment.getIntent(context, selectedCardIds, currentIndex)
