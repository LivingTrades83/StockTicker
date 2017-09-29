package com.github.premnirmal.ticker.model

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.github.premnirmal.ticker.AppPreferences
import com.github.premnirmal.ticker.AppPreferences.Companion.toCommaSeparatedString
import com.github.premnirmal.ticker.components.AppClock
import com.github.premnirmal.ticker.components.InAppMessage
import com.github.premnirmal.ticker.components.Injector
import com.github.premnirmal.ticker.components.RxBus
import com.github.premnirmal.ticker.events.ErrorEvent
import com.github.premnirmal.ticker.events.RefreshEvent
import com.github.premnirmal.ticker.network.RobindahoodException
import com.github.premnirmal.ticker.network.SimpleSubscriber
import com.github.premnirmal.ticker.network.StocksApi
import com.github.premnirmal.ticker.network.data.Quote
import com.github.premnirmal.ticker.widget.WidgetDataProvider
import com.github.premnirmal.tickerwidget.R
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.exceptions.CompositeException
import io.reactivex.schedulers.Schedulers
import org.threeten.bp.DayOfWeek
import org.threeten.bp.Instant
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.TextStyle.SHORT
import timber.log.Timber
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by premnirmal on 2/28/16.
 */
@Singleton
class StocksProvider @Inject constructor() : IStocksProvider {

  companion object {

    fun List<Quote>.positionsToString(): String {
      val builder = StringBuilder()
      for (stock in this) {
        if (stock.isPosition) {
          builder.append(stock.symbol)
          builder.append(",")
          builder.append(stock.isPosition)
          builder.append(",")
          builder.append(stock.positionPrice)
          builder.append(",")
          builder.append(stock.positionShares)
          builder.append("\n")
        }
      }
      return builder.toString()
    }

    fun String.stringToPositions(): MutableList<Quote> {
      val tickerListCSV = ArrayList(Arrays.asList(
          *this.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()))
      val stockList = ArrayList<Quote>()
      var tickerFields: ArrayList<String>
      var tmpQuote: Quote
      for (tickerCSV in tickerListCSV) {
        tickerFields = ArrayList(Arrays.asList(
            *tickerCSV.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()))
        if (tickerFields.size >= 4 && java.lang.Boolean.parseBoolean(tickerFields[1])) {
          tmpQuote = Quote()
          tmpQuote.isPosition = true
          tmpQuote.symbol = tickerFields[0]
          tmpQuote.positionPrice = java.lang.Float.parseFloat(tickerFields[2])
          tmpQuote.positionShares = java.lang.Float.parseFloat(tickerFields[3]).toInt()
          stockList.add(tmpQuote)
        }
      }
      return stockList
    }

    internal val LAST_FETCHED = "LAST_FETCHED"
    internal val NEXT_FETCH = "NEXT_FETCH"
    internal val POSITION_LIST = "POSITION_LIST"
    internal val DEFAULT_STOCKS = "SPY,DIA,GOOG,AAPL,MSFT"

    val SORTED_STOCK_LIST = AppPreferences.SORTED_STOCK_LIST
  }

  @Inject lateinit internal var api: StocksApi
  @Inject lateinit internal var context: Context
  @Inject lateinit internal var preferences: SharedPreferences
  @Inject lateinit internal var appPreferences: AppPreferences
  @Inject lateinit internal var bus: RxBus
  @Inject lateinit internal var widgetDataProvider: WidgetDataProvider
  @Inject lateinit internal var alarmScheduler: AlarmScheduler
  @Inject lateinit internal var clock: AppClock
  @Inject lateinit internal var mainThreadHandler: Handler

  private val tickerList: MutableList<String>
  private val quoteList: MutableList<Quote> = ArrayList()
  private val positionList: MutableList<Quote>
  private var lastFetched: Long = 0L
  private var nextFetch: Long = 0L
  private val storage: StocksStorage
  private val exponentialBackoff: ExponentialBackoff
  private var backOffAttemptCount = 1

  init {
    Injector.appComponent.inject(this)
    storage = StocksStorage()
    exponentialBackoff = ExponentialBackoff()
    backOffAttemptCount = appPreferences.backOffAttemptCount()
    val tickerListVars = preferences.getString(SORTED_STOCK_LIST, DEFAULT_STOCKS)
    tickerList = ArrayList(Arrays.asList(
        *tickerListVars.split(",".toRegex()).dropLastWhile(String::isEmpty).toTypedArray()))

    positionList = preferences.getString(POSITION_LIST, "").stringToPositions()

    val tickerList = this.tickerList.toCommaSeparatedString()
    preferences.edit().putString(SORTED_STOCK_LIST, tickerList).apply()
    lastFetched = try {
      preferences.getLong(LAST_FETCHED, 0L)
    } catch (e: Exception) {
      0L
    }
    nextFetch = preferences.getLong(NEXT_FETCH, 0)
    if (lastFetched == 0L) {
      fetch().subscribe(SimpleSubscriber())
    } else {
      fetchLocal()
    }
  }

  private fun fetchLocal() {
    synchronized(quoteList, {
      quoteList.clear()
      quoteList.addAll(storage.readStocks())
      scheduleUpdate()
    })
  }

  private fun save() {
    preferences.edit()
        .putString(POSITION_LIST, positionList.positionsToString())
        .putString(SORTED_STOCK_LIST, tickerList.toCommaSeparatedString())
        .putLong(LAST_FETCHED, lastFetched)
        .apply()
    storage.saveStocks(quoteList)
  }

  override fun fetch(): Observable<List<Quote>> {
    if (tickerList.isEmpty()) {
      bus.post(ErrorEvent(context.getString(R.string.no_symbols_in_portfolio)))
      return Observable.error<List<Quote>>(Exception("No symbols in portfolio"))
          .subscribeOn(Schedulers.io())
          .observeOn(AndroidSchedulers.mainThread())
    } else {
      return api.getStocks(tickerList)
          .doOnSubscribe {
            appPreferences.setRefreshing(true)
            widgetDataProvider.broadcastUpdateAllWidgets()
          }
          .map { stocks ->
            if (stocks.isEmpty()) {
              bus.post(ErrorEvent(context.getString(R.string.no_symbols_in_portfolio)))
              throw RuntimeException("No symbols in portfolio")
            } else {
              synchronized(quoteList, {
                tickerList.clear()
                stocks.mapTo(tickerList) { it.symbol }
                quoteList.clear()
                quoteList.addAll(stocks)
                lastFetched = api.lastFetched
                save()
              })
              stocks
            }
          }
          .doOnNext { _ ->
            appPreferences.setRefreshing(false)
            synchronized(quoteList, {
              backOffAttemptCount = 1
              saveBackOffAttemptCount()
              scheduleUpdate(true)
            })
          }
          .doOnError { t ->
            appPreferences.setRefreshing(false)
            if (t is CompositeException) {
              for (exception in t.exceptions) {
                if (exception is RobindahoodException) {
                  exception.message?.let {
                    if (!bus.post(ErrorEvent(it))) {
                      mainThreadHandler.post {
                        InAppMessage.showToast(context, it)
                      }
                    }
                  }
                  break
                }
              }
            } else {
              Timber.e(t)
            }
            retryWithBackoff()
            widgetDataProvider.broadcastUpdateAllWidgets()
          }
          .subscribeOn(Schedulers.io())
          .observeOn(AndroidSchedulers.mainThread())
    }
  }

  override fun schedule() {
    scheduleUpdate()
  }

  private fun retryWithBackoff() {
    val backOffTimeMs = exponentialBackoff.getBackoffDurationMs(backOffAttemptCount++)
    saveBackOffAttemptCount()
    scheduleUpdateWithMs(backOffTimeMs)
  }

  private fun saveBackOffAttemptCount() {
    appPreferences.setBackOffAttemptCount(backOffAttemptCount)
  }

  private fun scheduleUpdate(refresh: Boolean = false) {
    scheduleUpdateWithMs(msToNextAlarm, refresh)
  }

  private val msToNextAlarm: Long
    get() = alarmScheduler.msToNextAlarm()

  private fun scheduleUpdateWithMs(msToNextAlarm: Long, refresh: Boolean = false) {
    val updateTime = alarmScheduler.scheduleUpdate(msToNextAlarm, context)
    nextFetch = updateTime.toInstant().toEpochMilli()
    preferences.edit().putLong(NEXT_FETCH, nextFetch).apply()
    appPreferences.setRefreshing(false)
    widgetDataProvider.broadcastUpdateAllWidgets()
    if (refresh) {
      bus.post(RefreshEvent())
    }
  }

  override fun addStock(ticker: String): Collection<String> {
    if (!tickerList.contains(ticker)) {
      tickerList.add(ticker)
      val quote = Quote()
      quote.symbol = ticker
      quoteList.add(quote)
      save()
      fetch().subscribe(SimpleSubscriber())
    }
    return tickerList
  }

  override fun addPosition(ticker: String, shares: Int, price: Float) {
    synchronized(quoteList, {
      var position = getStock(ticker)
      if (position == null) {
        position = Quote()
        position.symbol = ticker
      }
      if (!tickerList.contains(ticker)) {
        tickerList.add(ticker)
      }
      if (shares > 0) {
        position.isPosition = true
        position.positionPrice = price
        position.positionShares = shares
        positionList.remove(position)
        positionList.add(position)
        quoteList.remove(position)
        quoteList.add(position)
        save()
      } else {
        removePosition(ticker)
      }
    })
  }

  override fun removePosition(ticker: String) {
    val position = getStock(ticker) ?: return
    position.isPosition = false
    position.positionPrice = 0f
    position.positionShares = 0
    positionList.remove(position)
    save()
  }

  override fun addStocks(tickers: Collection<String>): Collection<String> {
    tickers
        .filterNot { tickerList.contains(it) }
        .forEach { tickerList.add(it) }
    save()
    fetch().subscribe(SimpleSubscriber())
    return tickerList
  }

  override fun removeStock(ticker: String): Collection<String> {
    synchronized(quoteList, {
      val ticker2 = "^" + ticker // in case it was an index
      tickerList.remove(ticker)
      tickerList.remove(ticker2)
      val dummy = Quote()
      val dummy2 = Quote()
      dummy.symbol = ticker
      dummy2.symbol = ticker2
      quoteList.remove(dummy)
      quoteList.remove(dummy2)
      positionList.remove(dummy)
      positionList.remove(dummy2)
      save()
      scheduleUpdateWithMs(msToNextAlarm)
      return tickerList
    })
  }

  override fun removeStocks(tickers: Collection<String>) {
    synchronized(quoteList, {
      tickers.forEach {
        val ticker2 = "^" + it // in case it was an index
        tickerList.remove(it)
        tickerList.remove(ticker2)
        val dummy = Quote()
        val dummy2 = Quote()
        dummy.symbol = it
        dummy2.symbol = ticker2
        quoteList.remove(dummy)
        quoteList.remove(dummy2)
        positionList.remove(dummy)
        positionList.remove(dummy2)
      }
      save()
      scheduleUpdateWithMs(msToNextAlarm)
    })
  }

  override fun getStock(ticker: String): Quote? {
    synchronized(quoteList, {
      val dummy = Quote()
      dummy.symbol = ticker
      val index = quoteList.indexOf(dummy)
      return if (index >= 0) {
        val stock = quoteList[index]
        stock
      } else {
        null
      }
    })
  }

  override fun getTickers(): List<String> = ArrayList(tickerList)

  override fun lastFetched(): String {
    val fetched: String
    fetched = if (lastFetched > 0L) {
      val instant = Instant.ofEpochMilli(lastFetched)
      val time = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
      createTimeString(time)
    } else {
      "--"
    }
    return fetched
  }

  private fun createTimeString(time: ZonedDateTime): String {
    val fetched: String
    val fetchedDayOfWeek = time.dayOfWeek.value
    val today = clock.todayZoned().dayOfWeek.value
    fetched = if (today == fetchedDayOfWeek) {
      AppPreferences.TIME_FORMATTER.format(time)
    } else {
      val day: String = DayOfWeek.from(time).getDisplayName(SHORT, Locale.getDefault())
      val timeStr: String = AppPreferences.TIME_FORMATTER.format(time)
      "$timeStr $day"
    }
    return fetched
  }

  override fun nextFetch(): String {
    val fetch: String
    fetch = if (nextFetch > 0) {
      val instant = Instant.ofEpochMilli(nextFetch)
      val time = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
      createTimeString(time)
    } else {
      "--"
    }
    return fetch
  }
}