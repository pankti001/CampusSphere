package com.campussphere.app.presentation.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SkillPage { MARKET, OFFER, MINE, FORM, REQUESTS, REQUEST, SESSIONS, NOTICES, REVIEWS }

/** A pending "ask to learn": the message and, for a mutual exchange, which of the student's own skills they offer back. */
data class SkillAsk(val offer: SkillOffer, val message: String = "", val exchangeOfferId: Long? = null)

/** A pending cancel (with an optional reason) or review (rating and comment) of one session. */
data class SkillSessionAction(val session: SkillSession, val review: Boolean, val rating: Int = 5, val text: String = "")

data class SkillUi(
    val page: SkillPage = SkillPage.MARKET, val stack: List<SkillPage> = emptyList(),
    val loading: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    // Marketplace
    val query: String = "", val category: String? = null, val level: String? = null, val mode: String? = null,
    val offers: List<SkillOffer> = emptyList(), val marketLoaded: Boolean = false,
    // One offer and its provider's reviews
    val offer: SkillOffer? = null, val reviews: List<SkillReview> = emptyList(), val reviewsOf: String = "",
    val ask: SkillAsk? = null,
    // The student's own offers
    val mine: List<SkillOffer> = emptyList(), val form: SkillOfferForm? = null, val formError: String? = null,
    // Requests and sessions
    val incoming: Boolean = true, val requests: List<SkillRequest> = emptyList(),
    val request: SkillRequest? = null, val requestSessions: List<SkillSession> = emptyList(),
    val sessionForm: SkillSessionForm? = null, val sessionFormError: String? = null,
    val history: Boolean = false, val sessions: List<SkillSession> = emptyList(), val action: SkillSessionAction? = null,
    // Notifications
    val notices: List<SkillNotice> = emptyList(), val unread: Long = 0,
)

class SkillViewModel(private val repo: SkillRepository, private val chats: ChatRepository, private val auth: () -> String,
                     private val lost: (Int) -> Unit, private val openChat: (Long) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(SkillUi())
    val ui: StateFlow<SkillUi> = state.asStateFlow()
    private var search: Job? = null

    private fun message(e: Exception): String {
        val status = (e as? SkillFailure)?.status ?: (e as? ChatFailure)?.status
        if (status == 401 || status == 403) lost(status)
        return e.message ?: "Something went wrong. Try again."
    }

    private fun work(done: String? = null, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
                if (done != null) state.update { it.copy(notice = done) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.update { it.copy(error = message(e), notice = null) }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    private fun go(page: SkillPage) = state.update {
        if (it.page == page) it else it.copy(page = page, stack = it.stack + it.page, error = null, notice = null)
    }

    /** Back inside Skill Exchange; false when already on the marketplace (the screen then closes). */
    fun back(): Boolean {
        val s = state.value
        if (s.ask != null) { state.update { it.copy(ask = null) }; return true }
        if (s.action != null) { state.update { it.copy(action = null) }; return true }
        val previous = s.stack.lastOrNull() ?: return false
        state.update { it.copy(page = previous, stack = it.stack.dropLast(1), error = null, notice = null, form = null,
            formError = null, sessionForm = null, sessionFormError = null) }
        refreshPage()
        return true
    }

    /** Opens the first page; reloads it when the student comes back to the screen. */
    fun start() {
        if (!state.value.marketLoaded) loadMarket()
        refreshUnread()
    }

    private fun refreshUnread() = viewModelScope.launch {
        runCatching { repo.unread(auth()) }.onSuccess { n -> state.update { it.copy(unread = n) } }
    }

    private fun refreshPage() {
        when (state.value.page) {
            SkillPage.MARKET -> loadMarket()
            SkillPage.MINE -> openMine()
            SkillPage.REQUESTS -> openRequests(state.value.incoming)
            SkillPage.SESSIONS -> openSessions(state.value.history)
            SkillPage.REQUEST -> state.value.request?.let { openRequest(it.id) }
            else -> Unit
        }
    }

    // ---- Marketplace ----

    private fun loadMarket() {
        val s = state.value
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val list = repo.browse(auth(), s.query, s.category, s.level, s.mode)
                state.update { it.copy(offers = list, marketLoaded = true) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.update { it.copy(error = message(e)) }
            } finally { state.update { it.copy(loading = false) } }
        }
    }

    /** Searches a moment after typing stops. */
    fun setQuery(text: String) {
        state.update { it.copy(query = text.take(60)) }
        search?.cancel()
        search = viewModelScope.launch { delay(350); loadMarket() }
    }

    fun setFilters(category: String? = state.value.category, level: String? = state.value.level, mode: String? = state.value.mode) {
        state.update { it.copy(category = category, level = level, mode = mode) }
        loadMarket()
    }

    fun openOffer(id: Long) = work {
        val offer = repo.offer(auth(), id)
        state.update { it.copy(offer = offer, reviews = emptyList()) }
        go(SkillPage.OFFER)
        runCatching { repo.reviews(auth(), offer.providerId) }.onSuccess { r -> state.update { it.copy(reviews = r) } }
    }

    fun openReviews() {
        val offer = state.value.offer ?: return
        state.update { it.copy(reviewsOf = offer.provider) }
        go(SkillPage.REVIEWS)
    }

    // ---- Asking to learn ----

    fun startAsk() = work {
        val offer = state.value.offer ?: return@work
        val mine = repo.mine(auth())
        state.update { it.copy(mine = mine, ask = SkillAsk(offer)) }
    }

    fun editAsk(ask: SkillAsk) = state.update { it.copy(ask = ask.copy(message = ask.message.take(500))) }

    fun sendAsk() {
        val ask = state.value.ask ?: return
        work("Request sent to ${ask.offer.provider.substringBefore(' ')}.") {
            repo.ask(auth(), ask.offer.id, ask.exchangeOfferId, ask.message)
            val fresh = repo.offer(auth(), ask.offer.id)
            state.update { s -> s.copy(ask = null, offer = fresh, offers = s.offers.map { if (it.id == fresh.id) fresh else it }) }
        }
    }

    /** Opens (or starts) a private chat with a classmate from Skill Exchange. */
    fun chatWith(studentId: Long) = work {
        val conversation = chats.direct(auth(), studentId)
        openChat(conversation.id)
    }

    // ---- My skills ----

    fun openMine() = work {
        val mine = repo.mine(auth())
        state.update { it.copy(mine = mine) }
        go(SkillPage.MINE)
    }

    fun newOffer() { state.update { it.copy(form = SkillOfferForm(), formError = null) }; go(SkillPage.FORM) }
    fun editOffer(offer: SkillOffer) { state.update { it.copy(form = offer.toForm(), formError = null) }; go(SkillPage.FORM) }
    fun editForm(form: SkillOfferForm) = state.update { it.copy(form = form, formError = null) }

    fun saveOffer() {
        val form = state.value.form ?: return
        skillOfferProblem(form)?.let { problem -> state.update { it.copy(formError = problem) }; return }
        work(if (form.id == null) "Your skill is now visible to classmates in your college." else "Skill updated.") {
            repo.save(auth(), form)
            val mine = repo.mine(auth())
            // Straight back to the list, in the same step, so the page is never blank.
            state.update { it.copy(mine = mine, form = null, page = SkillPage.MINE,
                stack = it.stack.dropLastWhile { p -> p == SkillPage.FORM || p == SkillPage.MINE }) }
        }
    }

    fun pause(offer: SkillOffer) = work(if (offer.status == "ACTIVE") "Paused: classmates no longer see it." else "Visible again.") {
        repo.pause(auth(), offer.id, offer.status == "ACTIVE")
        val mine = repo.mine(auth())
        state.update { it.copy(mine = mine) }
    }

    fun remove(offer: SkillOffer) = work("Removed ${offer.name}.") {
        repo.archive(auth(), offer.id)
        val mine = repo.mine(auth())
        state.update { it.copy(mine = mine) }
    }

    // ---- Requests ----

    fun openRequests(incoming: Boolean = state.value.incoming) = work {
        val list = repo.requests(auth(), if (incoming) "incoming" else "outgoing")
        state.update { it.copy(incoming = incoming, requests = list) }
        go(SkillPage.REQUESTS)
    }

    fun openRequest(id: Long) = work {
        val request = repo.request(auth(), id)
        val sessions = (repo.sessions(auth(), false) + repo.sessions(auth(), true)).filter { it.requestId == id }
            .distinctBy { it.id }.sortedByDescending { it.startsAt }
        state.update { it.copy(request = request, requestSessions = sessions) }
        go(SkillPage.REQUEST)
    }

    fun decide(request: SkillRequest, action: String) = work(when (action) {
        "accept" -> "Accepted. Now propose a session time."
        "reject" -> "Request declined."
        else -> "Request cancelled."
    }) {
        val updated = repo.decide(auth(), request.id, action)
        state.update { s -> s.copy(requests = s.requests.map { if (it.id == updated.id) updated else it },
            request = if (s.request?.id == updated.id) updated else s.request) }
        if (state.value.page == SkillPage.REQUEST) {
            val sessions = (repo.sessions(auth(), false) + repo.sessions(auth(), true)).filter { it.requestId == updated.id }
                .distinctBy { it.id }.sortedByDescending { it.startsAt }
            state.update { it.copy(requestSessions = sessions) }
        }
    }

    fun startSessionForm() {
        val request = state.value.request ?: return
        // In a one-way request only the provider teaches; in an exchange either can.
        state.update { it.copy(sessionForm = SkillSessionForm(teach = request.incoming), sessionFormError = null) }
    }

    fun editSessionForm(form: SkillSessionForm) = state.update { it.copy(sessionForm = form, sessionFormError = null) }

    fun proposeSession() {
        val request = state.value.request ?: return
        val form = state.value.sessionForm ?: return
        skillSessionTimes(form, java.time.ZonedDateTime.now()).exceptionOrNull()?.let { problem ->
            state.update { it.copy(sessionFormError = problem.message) }
            return
        }
        work("Session proposed. ${request.partner.substringBefore(' ')} will be asked to confirm.") {
            val session = repo.propose(auth(), request.id, form)
            state.update { it.copy(sessionForm = null, requestSessions = listOf(session) + it.requestSessions) }
        }
    }

    // ---- Sessions ----

    fun openSessions(history: Boolean = state.value.history) = work {
        val list = repo.sessions(auth(), history)
        state.update { it.copy(history = history, sessions = list) }
        go(SkillPage.SESSIONS)
    }

    private fun replace(session: SkillSession) = state.update { s ->
        s.copy(sessions = s.sessions.map { if (it.id == session.id) session else it },
            requestSessions = s.requestSessions.map { if (it.id == session.id) session else it })
    }

    fun confirm(session: SkillSession) = work("Session confirmed.") { replace(repo.confirm(auth(), session.id)) }
    fun complete(session: SkillSession) = work("Marked as completed. You can now leave a review.") { replace(repo.complete(auth(), session.id)) }

    fun startCancel(session: SkillSession) = state.update { it.copy(action = SkillSessionAction(session, review = false)) }
    fun startReview(session: SkillSession) = state.update { it.copy(action = SkillSessionAction(session, review = true)) }
    fun editAction(action: SkillSessionAction) = state.update {
        it.copy(action = action.copy(text = action.text.take(if (action.review) 1000 else 300), rating = action.rating.coerceIn(1, 5)))
    }

    fun finishAction() {
        val action = state.value.action ?: return
        work(if (action.review) "Thank you for your review." else "Session cancelled.") {
            if (action.review) {
                repo.review(auth(), action.session.id, action.rating, action.text)
                replace(action.session.copy(canReview = false, myRating = action.rating))
            } else replace(repo.cancel(auth(), action.session.id, action.text))
            state.update { it.copy(action = null) }
        }
    }

    // ---- Notifications ----

    fun openNotices() = work {
        val list = repo.notices(auth())
        state.update { it.copy(notices = list) }
        go(SkillPage.NOTICES)
    }

    /** Marks a notice read and opens what it is about. */
    fun openNotice(notice: SkillNotice) {
        if (notice.readAt == null) viewModelScope.launch {
            runCatching { repo.read(auth(), notice.id) }.onSuccess {
                state.update { s -> s.copy(unread = (s.unread - 1).coerceAtLeast(0),
                    notices = s.notices.map { if (it.id == notice.id) it.copy(readAt = "read") else it }) }
            }
        }
        when {
            notice.requestId != null -> openRequest(notice.requestId)
            notice.sessionId != null -> openSessions(notice.kind == "REVIEW_NEW")
        }
    }
}
