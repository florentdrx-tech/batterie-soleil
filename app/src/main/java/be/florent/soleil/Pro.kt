package be.florent.soleil

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/** Version Pro : abonnement annuel Google Play « pro_annuel » (6,99 €/an, 14 jours d'essai). */
object Pro : PurchasesUpdatedListener {

    const val PRODUCT = "pro_annuel"
    const val MANAGE_URL = "https://play.google.com/store/account/subscriptions?package=be.florent.soleil&sku=$PRODUCT"

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    /** Appelé quand le statut Pro ou le prix change (sur le fil principal). */
    var onChange: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("soleil", Context.MODE_PRIVATE)

    /** Version de test : APK GitHub avec Pro débloqué (sauf si on simule la version gratuite). */
    fun isBeta() = BuildConfig.PRO_UNLOCKED

    fun simFree(ctx: Context) = prefs(ctx).getBoolean("pro_sim_free", false)
    fun setSimFree(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("pro_sim_free", v).apply(); notifyChange() }

    fun isPro(ctx: Context): Boolean =
        (isBeta() && !simFree(ctx)) || prefs(ctx).getBoolean("pro_active", false)

    private fun setActive(ctx: Context, v: Boolean) {
        val p = prefs(ctx)
        if (p.getBoolean("pro_active", false) != v) {
            p.edit().putBoolean("pro_active", v).apply()
        }
        notifyChange()
    }

    private fun notifyChange() = main.post { onChange?.invoke() }

    // ---------- Connexion à Google Play ----------

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (client?.isReady == true) { refresh(); return }
        val c = BillingClient.newBuilder(ctx.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryDetails()
                    refresh()
                }
            }
            override fun onBillingServiceDisconnected() { client = null }
        })
    }

    private fun queryDetails() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        ).build()
        c.queryProductDetailsAsync(params) { r, result ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                details = result.productDetailsList.firstOrNull()
                notifyChange()
            }
        }
    }

    /** Vérifie les abonnements actifs (au démarrage, et « Restaurer mes achats »). */
    fun refresh() {
        val c = client ?: return
        val ctx = appCtx ?: return
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { r, purchases ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(ctx, purchases)
        }
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        val ctx = appCtx ?: return
        if (r.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(ctx, purchases)
    }

    private fun handle(ctx: Context, purchases: List<Purchase>) {
        val mine = purchases.filter { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        mine.filter { !it.isAcknowledged }.forEach { p ->
            client?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
            ) { }
        }
        setActive(ctx, mine.isNotEmpty())
    }

    // ---------- Offre et prix ----------

    private fun offer(): ProductDetails.SubscriptionOfferDetails? {
        val offers = details?.subscriptionOfferDetails ?: return null
        // De préférence l'offre avec essai gratuit (une phase à 0 €), sinon le forfait de base
        return offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
            ?: offers.firstOrNull()
    }

    fun hasTrial(): Boolean = offer()?.pricingPhases?.pricingPhaseList?.any { it.priceAmountMicros == 0L } == true

    /** Prix annuel affiché par Google Play (dans la devise de l'utilisateur), ou le prix prévu. */
    fun price(): String =
        offer()?.pricingPhases?.pricingPhaseList?.lastOrNull { it.priceAmountMicros > 0L }?.formattedPrice
            ?: L.t("6,99 €", "€ 6,99")

    fun available(): Boolean = details != null && offer() != null

    fun buy(act: Activity): Boolean {
        val c = client ?: return false
        val d = details ?: return false
        val o = offer() ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(d)
                    .setOfferToken(o.offerToken)
                    .build()
            )
        ).build()
        return c.launchBillingFlow(act, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    // ---------- Fenêtre « Passer à Pro » ----------

    fun showPaywall(act: Activity) {
        start(act)
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_pro)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        dlg.findViewById<TextView>(R.id.pTitle).text = L.t("⭐ Soleil & Batterie Pro", "⭐ Zon & Batterij Pro")
        dlg.findViewById<TextView>(R.id.pSub).text =
            L.t("Tire le maximum de tes panneaux et de ta batterie.", "Haal het maximum uit je panelen en je batterij.")
        val list = dlg.findViewById<LinearLayout>(R.id.pList)
        val dp = act.resources.displayMetrics.density
        val items = if (L.nl) listOf(
            "📅" to "Voorspelling voor 7 dagen",
            "🔋" to "Batterijniveau en verwacht niveau vanavond",
            "🧺" to "Wat aanzetten? Beste moment en kost per toestel",
            "💶" to "Tarieven: daluren, dag/nacht, capaciteitstarief",
            "☀️" to "Twee velden met verschillende oriëntatie",
            "🌳" to "Schaduw van bomen en gebouwen per seizoen",
            "🩺" to "Diagnose en adviezen bij lage productie",
            "🔔" to "Herinneringen 's ochtends en 's avonds",
            "💡" to "Advies van de dag in de widget"
        ) else listOf(
            "📅" to "Prévisions sur 7 jours",
            "🔋" to "Niveau de batterie et niveau prévu ce soir",
            "🧺" to "Que lancer ? Meilleur moment et coût par appareil",
            "💶" to "Tarifs : heures creuses, bi-horaire, tarif capacitaire",
            "☀️" to "Deux champs de panneaux d'orientations différentes",
            "🌳" to "Ombres des arbres et bâtiments selon la saison",
            "🩺" to "Diagnostic et conseils en cas de production faible",
            "🔔" to "Rappels du matin et du soir",
            "💡" to "Conseil du jour dans le widget"
        )
        items.forEach { (icon, txt) ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            }
            row.addView(TextView(act).apply { text = icon; textSize = 18f },
                LinearLayout.LayoutParams((34 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(act).apply {
                text = txt; textSize = 14f; setTextColor(act.getColor(R.color.fg))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }

        val priceTv = dlg.findViewById<TextView>(R.id.pPrice)
        val buy = dlg.findViewById<Button>(R.id.pBuy)
        val restore = dlg.findViewById<TextView>(R.id.pRestore)
        fun render() {
            val p = price()
            priceTv.text = if (!available()) L.t(
                "L'abonnement sera disponible dès la publication sur le Play Store.",
                "Het abonnement is beschikbaar zodra de app in de Play Store staat.")
            else if (hasTrial()) L.t("14 jours gratuits, puis $p par an", "14 dagen gratis, daarna $p per jaar")
            else L.t("$p par an", "$p per jaar")
            buy.text = if (hasTrial()) L.t("Essayer 14 jours gratuitement", "14 dagen gratis proberen")
                else L.t("S'abonner", "Abonneren")
            buy.isEnabled = available()
            buy.alpha = if (available()) 1f else 0.4f
        }
        render()
        val previous = onChange
        onChange = {
            previous?.invoke()
            render()
            if (isPro(act)) dlg.dismiss()
        }
        dlg.setOnDismissListener { onChange = previous }

        buy.setOnClickListener { buy(act) }
        restore.text = L.t("Restaurer mes achats", "Aankopen herstellen")
        restore.setOnClickListener { start(act); refresh() }
        dlg.findViewById<TextView>(R.id.pLegal).text = L.t(
            "Abonnement annuel renouvelé automatiquement. Résiliable à tout moment dans Google Play › Abonnements, au plus tard 24 h avant la fin de la période. L'essai gratuit n'est proposé qu'une fois par compte.",
            "Jaarabonnement, automatisch verlengd. Op elk moment opzegbaar in Google Play › Abonnementen, uiterlijk 24 u voor het einde van de periode. De gratis proefperiode geldt één keer per account.")
        dlg.findViewById<TextView>(R.id.pClose).apply {
            text = L.t("Plus tard", "Later")
            setOnClickListener { dlg.dismiss() }
        }
        dlg.show()
    }
}
