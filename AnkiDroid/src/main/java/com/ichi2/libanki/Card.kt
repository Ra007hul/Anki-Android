/*
 * Copyright (c) 2009 Daniel Svärd daniel.svard@gmail.com>
 * Copyright (c) 2011 Norbert Nagold norbert.nagold@gmail.com>
 * Copyright (c) 2014 Houssam Salem <houssam.salem.au@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.libanki

import androidx.annotation.VisibleForTesting
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.servicelayer.NoteService.avgEase
import com.ichi2.anki.utils.SECONDS_PER_DAY
import com.ichi2.libanki.Consts.CARD_QUEUE
import com.ichi2.libanki.Consts.CARD_TYPE
import com.ichi2.libanki.TemplateManager.TemplateRenderContext.TemplateRenderOutput
import com.ichi2.libanki.utils.TimeManager
import com.ichi2.utils.Assert
import com.ichi2.utils.LanguageUtil
import net.ankiweb.rsdroid.RustCleanup
import org.json.JSONObject
import java.util.*

/**
 * A Card is the ultimate entity subject to review; it encapsulates the scheduling parameters (from which to derive
 * the next interval), the note it is derived from (from which field data is retrieved), its own ownership (which deck it
 * currently belongs to), and the retrieval of presentation elements (filled-in templates).
 *
 * Card presentation has two components: the question (front) side and the answer (back) side. The presentation of the
 * card is derived from the template of the card's Card Type. The Card Type is a component of the Note Type (see Models)
 * that this card is derived from.
 *
 * This class is responsible for:
 * - Storing and retrieving database entries that map to Cards in the Collection
 * - Providing the HTML representation of the Card's question and answer
 * - Recording the results of review (answer chosen, time taken, etc)
 *
 * It does not:
 * - Generate new cards (see Collection)
 * - Store the templates or the style sheet (see Models)
 *
 * Type: 0=new, 1=learning, 2=due
 * Queue: same as above, and:
 * -1=suspended, -2=user buried, -3=sched buried
 * Due is used differently for different queues.
 * - new queue: note id or random int
 * - rev queue: integer day
 * - lrn queue: integer timestamp
 */
open class Card : Cloneable {

    /**
     * Time in MS when timer was started
     */
    var timerStarted: Long

    // Not in LibAnki. Record time spent reviewing in MS in order to restore when resuming.
    private var elapsedTime: Long = 0

    // BEGIN SQL table entries
    @set:VisibleForTesting
    var id: Long
    var nid: NoteId = 0
    var did: DeckId = 0
    var ord = 0
    var mod: Long = 0
    var usn = 0

    @get:CARD_TYPE
    @CARD_TYPE
    var type = 0

    @get:CARD_QUEUE
    @CARD_QUEUE
    var queue = 0
    var due: Long = 0
    var ivl = 0
    var factor = 0
    var reps = 0
        private set
    var lapses = 0
    var left = 0
    var oDue: Long = 0
    var oDid: DeckId = 0
    private var customData: String = ""
    private var originalPosition: Int? = null
    private var flags = 0

    // END SQL table entries
    var renderOutput: TemplateRenderOutput?
    private var note: Note?

    constructor(col: Collection) {
        timerStarted = 0L
        renderOutput = null
        note = null
        // to flush, set nid, ord, and due
        this.id = TimeManager.time.timestampID(col.db, "cards")
        did = 1
        this.type = Consts.CARD_TYPE_NEW
        queue = Consts.QUEUE_TYPE_NEW
        ivl = 0
        factor = 0
        reps = 0
        lapses = 0
        left = 0
        oDue = 0
        oDid = 0
        flags = 0
    }

    /** Construct an instance from a backend Card */
    constructor(card: anki.cards.Card) {
        timerStarted = 0L
        renderOutput = null
        note = null
        id = card.id
        loadFromBackendCard(card)
    }

    constructor(col: Collection, id: Long?) {
        timerStarted = 0L
        renderOutput = null
        note = null
        if (id != null) {
            this.id = id
            load(col)
        } else {
            // ephemeral card
            this.id = 0
        }
    }

    fun load(col: Collection) {
        val card = col.backend.getCard(id)
        loadFromBackendCard(card)
    }

    private fun loadFromBackendCard(card: anki.cards.Card) {
        renderOutput = null
        note = null
        id = card.id
        nid = card.noteId
        did = card.deckId
        ord = card.templateIdx
        mod = card.mtimeSecs
        usn = card.usn
        type = card.ctype
        queue = card.queue
        due = card.due.toLong() // TODO due should be an int
        ivl = card.interval
        factor = card.easeFactor
        reps = card.reps
        lapses = card.lapses
        left = card.remainingSteps
        oDue = card.originalDue.toLong() // TODO due should be an int
        oDid = card.originalDeckId
        flags = card.flags
        originalPosition = if (card.hasOriginalPosition()) card.originalPosition else null
        customData = card.customData
    }

    fun toBackendCard(): anki.cards.Card {
        val builder = anki.cards.Card.newBuilder()
            .setId(id)
            .setNoteId(nid)
            .setDeckId(did)
            .setTemplateIdx(ord)
            .setCtype(type)
            .setQueue(queue)
            .setDue(due.toInt())
            .setInterval(ivl)
            .setEaseFactor(factor)
            .setReps(reps)
            .setLapses(lapses)
            .setRemainingSteps(left)
            .setOriginalDue(oDue.toInt())
            .setOriginalDeckId(oDid)
            .setFlags(flags)
            .setCustomData(customData)
        originalPosition?.let { builder.setOriginalPosition(it) }
        return builder.build()
    }

    fun question(col: Collection, reload: Boolean = false, browser: Boolean = false): String {
        return renderOutput(col, reload, browser).questionAndStyle()
    }

    fun answer(col: Collection): String {
        return renderOutput(col).answerAndStyle()
    }

    @RustCleanup("legacy")
    fun css(col: Collection): String {
        return "<style>${renderOutput(col).css}</style>"
    }

    fun questionAvTags(col: Collection): List<AvTag> {
        return renderOutput(col).questionAvTags
    }

    fun answerAvTags(col: Collection): List<AvTag> {
        return renderOutput(col).answerAvTags
    }

    /**
     * @throws net.ankiweb.rsdroid.exceptions.BackendInvalidInputException: If the card does not exist
     */
    open fun renderOutput(col: Collection, reload: Boolean = false, browser: Boolean = false): TemplateRenderOutput {
        if (renderOutput == null || reload) {
            renderOutput = TemplateManager.TemplateRenderContext.fromExistingCard(col, this, browser).render()
        }
        return renderOutput!!
    }

    open fun note(col: Collection): Note {
        return note(col, false)
    }

    open fun note(col: Collection, reload: Boolean): Note {
        if (note == null || reload) {
            note = col.getNote(nid)
        }
        return note!!
    }

    // not in upstream
    open fun model(col: Collection): NotetypeJson {
        return note(col).notetype
    }

    fun template(col: Collection): JSONObject {
        val m = model(col)
        return if (m.isStd) {
            m.getJSONArray("tmpls").getJSONObject(ord)
        } else {
            model(col).getJSONArray("tmpls").getJSONObject(0)
        }
    }

    fun startTimer() {
        timerStarted = TimeManager.time.intTimeMS()
    }

    /**
     * Time limit for answering in milliseconds.
     */
    fun timeLimit(col: Collection): Int {
        val conf = col.decks.confForDid(if (!isInDynamicDeck) did else oDid)
        return conf.getInt("maxTaken") * 1000
    }

    /*
     * Time taken to answer card, in integer MS.
     */
    fun timeTaken(col: Collection): Int {
        // Indeed an int. Difference between two big numbers is still small.
        val total = (TimeManager.time.intTimeMS() - timerStarted).toInt()
        return Math.min(total, timeLimit(col))
    }

    /*
     * ***********************************************************
     * The methods below are not in LibAnki.
     * ***********************************************************
     */
    fun qSimple(col: Collection): String {
        return renderOutput(col).questionText
    }

    /**
     * Returns the answer with anything before the `<hr id=answer>` tag removed
     */
    fun pureAnswer(col: Collection): String {
        val s = renderOutput(col).answerText
        for (target in arrayOf("<hr id=answer>", "<hr id=\"answer\">")) {
            val pos = s.indexOf(target)
            if (pos == -1) continue
            return s.substring(pos + target.length).trim { it <= ' ' }
        }
        // neither found
        return s
    }

    /**
     * Save the currently elapsed reviewing time so it can be restored on resume.
     *
     * Use this method whenever a review session (activity) has been paused. Use the resumeTimer()
     * method when the session resumes to start counting review time again.
     */
    fun stopTimer() {
        elapsedTime = TimeManager.time.intTimeMS() - timerStarted
    }

    /**
     * Resume the timer that counts the time spent reviewing this card.
     *
     * Unlike the desktop client, AnkiDroid must pause and resume the process in the middle of
     * reviewing. This method is required to keep track of the actual amount of time spent in
     * the reviewer and *must* be called on resume before any calls to timeTaken(col) take place
     * or the result of timeTaken(col) will be wrong.
     */
    fun resumeTimer() {
        timerStarted = TimeManager.time.intTimeMS() - elapsedTime
    }

    @VisibleForTesting
    fun setReps(reps: Int): Int {
        return reps.also { this.reps = it }
    }

    fun showTimer(col: Collection): Boolean {
        val options = col.decks.confForDid(if (!isInDynamicDeck) did else oDid)
        return DeckConfig.parseTimerOpt(options, true)
    }

    public override fun clone(): Card {
        return try {
            super.clone() as Card
        } catch (e: CloneNotSupportedException) {
            throw RuntimeException(e)
        }
    }

    fun setNote(note: Note) {
        this.note = note
    }

    override fun toString(): String {
        val declaredFields = this.javaClass.declaredFields
        val members: MutableList<String?> = ArrayList(declaredFields.size)
        for (f in declaredFields) {
            try {
                // skip non-useful elements
                if (SKIP_PRINT.contains(f.name)) {
                    continue
                }
                members.add("'${f.name}': ${f[this]}")
            } catch (e: IllegalAccessException) {
                members.add("'${f.name}': N/A")
            } catch (e: IllegalArgumentException) {
                members.add("'${f.name}': N/A")
            }
        }
        return members.joinToString(",  ")
    }

    override fun equals(other: Any?): Boolean {
        return if (other is Card) {
            this.id == other.id
        } else {
            super.equals(other)
        }
    }

    override fun hashCode(): Int {
        // Map a long to an int. For API>=24 you would just do `Long.hashCode(this.getId())`
        return (this.id xor (this.id ushr 32)).toInt()
    }

    fun userFlag(): Int {
        return intToFlag(flags)
    }

    @VisibleForTesting
    fun setFlag(flag: Int) {
        flags = flag
    }

    fun setUserFlag(flag: Int) {
        flags = setFlagInInt(flags, flag)
    }

    // not in Anki.
    fun dueString(col: Collection): String {
        var t = nextDue(col)
        if (queue < 0) {
            t = "($t)"
        }
        return t
    }

    // as in Anki aqt/browser.py
    @VisibleForTesting
    fun nextDue(col: Collection): String {
        val date: Long
        val due = due
        date = if (isInDynamicDeck) {
            return AnkiDroidApp.appResources.getString(R.string.card_browser_due_filtered_card)
        } else if (queue == Consts.QUEUE_TYPE_LRN) {
            due
        } else if (queue == Consts.QUEUE_TYPE_NEW || type == Consts.CARD_TYPE_NEW) {
            return java.lang.Long.valueOf(due).toString()
        } else if (queue == Consts.QUEUE_TYPE_REV || queue == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN || type == Consts.CARD_TYPE_REV && queue < 0) {
            val time = TimeManager.time.intTime()
            val nbDaySinceCreation = due - col.sched.today
            time + nbDaySinceCreation * SECONDS_PER_DAY
        } else {
            return ""
        }
        return LanguageUtil.getShortDateFormatFromS(date)
    } // In Anki Desktop, a card with oDue <> 0 && oDid == 0 is not marked as dynamic.

    fun avgEaseOfNote(col: Collection) = avgEase(col, note(col))

    /** Non libAnki  */
    val isInDynamicDeck: Boolean
        get() = // In Anki Desktop, a card with oDue <> 0 && oDid == 0 is not marked as dynamic.
            oDid != 0L
    val isReview: Boolean
        get() = this.type == Consts.CARD_TYPE_REV && queue == Consts.QUEUE_TYPE_REV
    val isNew: Boolean
        get() = this.type == Consts.CARD_TYPE_NEW

    /** A cache represents an intermediary step between a card id and a card object. Creating a Card has some fixed cost
     * in term of database access. Using an id has an unknown cost: none if the card is never accessed, heavy if the
     * card is accessed a lot of time. CardCache ensure that the cost is paid at most once, by waiting for first access
     * to load the data, and then saving them. Since CPU and RAM is usually less of a bottleneck than database access,
     * it may often be worth using this cache.
     *
     * Beware that the card is loaded only once. Change in the database are not reflected, so use it only if you can
     * safely assume that the card has not changed. That is
     * long id;
     * Card card = col.getCard(id);
     * ....
     * Card card2 = col.getCard(id);
     * is not equivalent to
     * long id;
     * Card.Cache cache = new Cache(col, id);
     * Card card = cache.getCard();
     * ....
     * Card card2 = cache.getCard();
     *
     * It is equivalent to:
     * long id;
     * Card.Cache cache = new Cache(col, id);
     * Card card = cache.getCard();
     * ....
     * cache.reload();
     * Card card2 = cache.getCard();
     */
    open class Cache : Cloneable {
        val col: Collection
        val id: Long
        private var _card: Card? = null

        constructor(col: Collection, id: Long) {
            this.col = col
            this.id = id
        }

        /** Copy of cache. Useful to create a copy of a subclass without loosing card if it is loaded.  */
        protected constructor(cache: Cache) {
            col = cache.col
            this.id = cache.id
            _card = cache._card
        }

        /**
         * The card with id given at creation. Note that it has content of the time at which the card was loaded, which
         * may have changed in database. So it is not equivalent to getCol().getCard(getId()). If you need fresh data, reload
         * first. */
        @get:Synchronized
        val card: Card
            get() {
                if (_card == null) {
                    _card = col.getCard(this.id)
                }
                return _card!!
            }

        /** Next access to card will reload the card from the database.  */
        @Synchronized
        open fun reload() {
            _card = null
        }

        override fun hashCode(): Int {
            return java.lang.Long.valueOf(this.id).hashCode()
        }

        /** The cloned version represents the same card but data are not loaded.  */
        public override fun clone(): Cache {
            return Cache(col, this.id)
        }

        override fun equals(other: Any?): Boolean {
            return if (other !is Cache) {
                false
            } else {
                this.id == other.id
            }
        }
    }

    companion object {
        const val TYPE_REV = 2

        // A list of class members to skip in the toString() representation
        val SKIP_PRINT: Set<String> = HashSet(
            listOf(
                "SKIP_PRINT", "\$assertionsDisabled", "TYPE_LRN",
                "TYPE_NEW", "TYPE_REV", "mNote", "mQA", "mCol", "mTimerStarted", "mTimerStopped"
            )
        )

        fun intToFlag(flags: Int): Int {
            // setting all bits to 0, except the three first one.
            // equivalent to `mFlags % 8`. Used this way to copy Anki.
            return flags and 7
        }

        fun setFlagInInt(flags: Int, flag: Int): Int {
            Assert.that(0 <= flag, "flag to set is negative")
            Assert.that(flag <= 7, "flag to set is greater than 7.")
            // Setting the 3 firsts bits to 0, keeping the remaining.
            val extraData = flags and 7.inv()
            // flag in 3 fist bits, same data as in mFlags everywhere else
            return extraData or flag
        }
    }
}

/** @see Card.renderOutput */
context (Collection)
fun Card.renderOutput(reload: Boolean = false, browser: Boolean = false) =
    this@Card.renderOutput(this@Collection, reload, browser)

/** @see Card.note */
context (Collection)
fun Card.note() =
    this@Card.note(this@Collection)

/** @see Card.timeTaken */
context (Collection)
fun Card.timeTaken() =
    this@Card.timeTaken(this@Collection)

/** @see Card.timeLimit */
context (Collection)
fun Card.timeLimit() =
    this@Card.timeLimit(this@Collection)
