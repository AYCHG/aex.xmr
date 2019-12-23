/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.main.offer.xmr.takeoffer;

import bisq.desktop.Navigation;
import bisq.desktop.main.offer.xmr.XmrOfferDataModel;
import bisq.desktop.main.overlays.popups.Popup;
import bisq.desktop.util.GUIUtil;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.btc.TxFeeEstimationService;
import bisq.core.btc.listeners.BalanceListener;
import bisq.core.btc.model.AddressEntry;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.xmr.wallet.XmrRestrictions;
import bisq.core.filter.FilterManager;
import bisq.core.locale.CurrencyUtil;
import bisq.core.locale.Res;
import bisq.core.monetary.Price;
import bisq.core.monetary.Volume;
import bisq.core.offer.Offer;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OfferUtil;
import bisq.core.offer.XmrOfferUtil;
import bisq.core.payment.HalCashAccount;
import bisq.core.payment.PaymentAccount;
import bisq.core.payment.PaymentAccountUtil;
import bisq.core.payment.payload.PaymentMethod;
import bisq.core.provider.fee.XmrFeeService;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.trade.TradeManager;
import bisq.core.trade.handlers.TradeResultHandler;
import bisq.core.user.Preferences;
import bisq.core.user.User;
import bisq.core.util.BsqFormatter;
import bisq.core.util.XmrCoinUtil;
import bisq.core.xmr.wallet.XmrWalletRpcWrapper;
import bisq.network.p2p.P2PService;

import bisq.common.util.Tuple2;

import bisq.core.xmr.XmrCoin;
import bisq.core.xmr.jsonrpc.result.Address;
import bisq.core.xmr.jsonrpc.result.MoneroTx;
import bisq.core.xmr.listeners.XmrBalanceListener;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;

import com.google.inject.Inject;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import javafx.collections.ObservableList;

import java.util.Set;

import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Domain for that UI element.
 * Note that the create offer domain has a deeper scope in the application domain (TradeManager).
 * That model is just responsible for the domain specific parts displayed needed in that UI element.
 */
class XmrTakeOfferDataModel extends XmrOfferDataModel {
    private final TradeManager tradeManager;
    private final BtcWalletService btcWalletService;
    private final BsqWalletService bsqWalletService;
    private final XmrWalletRpcWrapper xmrWalletWrapper;
    private final User user;
    private final XmrFeeService feeService;
    private final FilterManager filterManager;
    private final Preferences preferences;
    private final TxFeeEstimationService txFeeEstimationService;
    private final PriceFeedService priceFeedService;
    private final AccountAgeWitnessService accountAgeWitnessService;
    private final Navigation navigation;
    private final P2PService p2PService;
    private final BsqFormatter bsqFormatter;

    private XmrCoin txFeeFromXmrFeeService;
    private XmrCoin securityDeposit;
    // XmrCoin feeFromFundingTx = XmrCoin.NEGATIVE_SATOSHI;

    private Offer offer;

    // final BooleanProperty isFeeFromFundingTxSufficient = new SimpleBooleanProperty();
    // final BooleanProperty isMainNet = new SimpleBooleanProperty();
    private final ObjectProperty<XmrCoin> amount = new SimpleObjectProperty<>();
    final ObjectProperty<Volume> volume = new SimpleObjectProperty<>();

    private XmrBalanceListener balanceListener;
    private PaymentAccount paymentAccount;
    private boolean isTabSelected;
    Price tradePrice;
    // 260 kb is size of typical trade fee tx with 1 input but trade tx (deposit and payout) are larger so we adjust to 320
    private int feeTxSize = 320;
    private boolean freezeFee;
    private XmrCoin txFeePerByteFromXmrFeeService;
    private String btcToXmrExchangeRate;
    private String bsqToXmrExchangeRate;
    

    //TODO(niyid) Replace BtcWalletService functions with XmrWalletRpcWrapper functions; then completely remove BtcWalletService
    //TODO(niyid) paymentAccount will provide a bridge to taker's xmr-wallet-rpc instance and wallet.
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor, lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////


    @Inject
    XmrTakeOfferDataModel(TradeManager tradeManager,
    		           BtcWalletService btcWalletService,
                       BsqWalletService bsqWalletService,
                       XmrWalletRpcWrapper xmrWalletWrapper,
                       User user, XmrFeeService feeService,
                       FilterManager filterManager,
                       Preferences preferences,
                       TxFeeEstimationService txFeeEstimationService,
                       PriceFeedService priceFeedService,
                       AccountAgeWitnessService accountAgeWitnessService,
                       Navigation navigation,
                       P2PService p2PService,
                       BsqFormatter bsqFormatter
                       
    ) {
        super(bsqWalletService, xmrWalletWrapper, priceFeedService, bsqFormatter);

        this.tradeManager = tradeManager;
        this.btcWalletService = btcWalletService;
        this.bsqWalletService = bsqWalletService;
        this.xmrWalletWrapper = xmrWalletWrapper;
        this.user = user;
        this.feeService = feeService;
        this.filterManager = filterManager;
        this.preferences = preferences;
        this.txFeeEstimationService = txFeeEstimationService;
        this.priceFeedService = priceFeedService;
        this.accountAgeWitnessService = accountAgeWitnessService;
        this.navigation = navigation;
        this.p2PService = p2PService;
        this.bsqFormatter = bsqFormatter;
    }

    @Override
    protected void activate() {
        // when leaving screen we reset state
        offer.setState(Offer.State.UNKNOWN);

        addListeners();

        updateBalance();

        // TODO In case that we have funded but restarted, or canceled but took again the offer we would need to
        // store locally the result when we received the funding tx(s).
        // For now we just ignore that rare case and bypass the check by setting a sufficient value
        // if (isWalletFunded.get())
        //     feeFromFundingTxProperty.set(FeePolicy.getMinRequiredFeeForFundingTx());

        if (isTabSelected)
            priceFeedService.setCurrencyCode(offer.getCurrencyCode());

        if (canTakeOffer()) {
            tradeManager.checkOfferAvailability(offer,
                    () -> {
                    },
                    errorMessage -> new Popup<>().warning(errorMessage).show());
        }
        btcToXmrExchangeRate = offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE);
        bsqToXmrExchangeRate = offer.getExtraDataMap().get(OfferPayload.BSQ_TO_XMR_RATE);
    }

    @Override
    protected void deactivate() {
        removeListeners();
        if (offer != null)
            tradeManager.onCancelAvailabilityRequest(offer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // called before activate
    void initWithData(Offer offer) {
        this.offer = offer;
        tradePrice = offer.getPrice();

        ObservableList<PaymentAccount> possiblePaymentAccounts = getPossiblePaymentAccounts();
        checkArgument(!possiblePaymentAccounts.isEmpty(), "possiblePaymentAccounts.isEmpty()");
        paymentAccount = getLastSelectedPaymentAccount();

        this.amount.set(XmrCoin.valueOf(Math.min(XmrCoin.fromCoin2XmrCoin(offer.getAmount(), "BTC", btcToXmrExchangeRate).value, getMaxTradeLimit())));

        securityDeposit = offer.getDirection() == OfferPayload.Direction.SELL ?
                XmrCoin.fromCoin2XmrCoin(getBuyerSecurityDeposit(), "BTC", btcToXmrExchangeRate) :
                	XmrCoin.fromCoin2XmrCoin(getSellerSecurityDeposit(), "BTC", btcToXmrExchangeRate);

        // Taker pays 3 times the tx fee (taker fee, deposit, payout) because the mining fee might be different when maker created the offer
        // and reserved his funds. Taker creates at least taker fee and deposit tx at nearly the same moment. Just the payout will
        // be later and still could lead to issues if the required fee changed a lot in the meantime. using RBF and/or
        // multiple batch-signed payout tx with different fees might be an option but RBF is not supported yet in BitcoinJ
        // and batched txs would add more complexity to the trade protocol.

        // A typical trade fee tx has about 260 bytes (if one input). The trade txs has about 336-414 bytes.
        // We use 320 as a average value.

        // trade fee tx: 260 bytes (1 input)
        // deposit tx: 336 bytes (1 MS output+ OP_RETURN) - 414 bytes (1 MS output + OP_RETURN + change in case of smaller trade amount)
        // payout tx: 371 bytes
        // disputed payout tx: 408 bytes

        // Set the default values (in rare cases if the fee request was not done yet we get the hard coded default values)
        // But the "take offer" happens usually after that so we should have already the value from the estimation service.
        txFeePerByteFromXmrFeeService = feeService.getTxFeePerByte();
        txFeeFromXmrFeeService = getTxFeeBySize(feeTxSize);

        // We request to get the actual estimated fee
        log.info("Start requestTxFee: txFeeFromXmrFeeService={}", txFeeFromXmrFeeService);
        feeService.requestFees(() -> {
            if (!freezeFee) {
                txFeePerByteFromXmrFeeService = feeService.getTxFeePerByte();
                txFeeFromXmrFeeService = getTxFeeBySize(feeTxSize);
                calculateTotalToPay();
                log.info("Completed requestTxFee: txFeeFromXmrFeeService={}", txFeeFromXmrFeeService);
            } else {
                log.debug("We received the tx fee response after we have shown the funding screen and ignore that " +
                        "to avoid that the total funds to pay changes due changed tx fees.");
            }
        });

        calculateVolume();
        calculateTotalToPay();

        //TODO(niyid) No Address
        balanceListener = new XmrBalanceListener(null) {
            @Override
            public void onBalanceChanged(XmrCoin balance, MoneroTx tx) {
                updateBalance();

                /*if (isMainNet.get()) {
                    SettableFuture<XmrCoin> future = blockchainService.requestFee(tx.getHashAsString());
                    Futures.addCallback(future, new FutureCallback<XmrCoin>() {
                        public void onSuccess(XmrCoin fee) {
                            UserThread.execute(() -> setFeeFromFundingTx(fee));
                        }

                        public void onFailure(@NotNull Throwable throwable) {
                            UserThread.execute(() -> new Popup<>()
                                    .warning("We did not get a response for the request of the mining fee used " +
                                            "in the funding transaction.\n\n" +
                                            "Are you sure you used a sufficiently high fee of at least " +
                                            formatter.formatCoinWithCode(FeePolicy.getMinRequiredFeeForFundingTx()) + "?")
                                    .actionButtonText("Yes, I used a sufficiently high fee.")
                                    .onAction(() -> setFeeFromFundingTx(FeePolicy.getMinRequiredFeeForFundingTx()))
                                    .closeButtonText("No. Let's cancel that payment.")
                                    .onClose(() -> setFeeFromFundingTx(XmrCoin.NEGATIVE_SATOSHI))
                                    .show());
                        }
                    });
                } else {
                    setFeeFromFundingTx(FeePolicy.getMinRequiredFeeForFundingTx());
                    isFeeFromFundingTxSufficient.set(feeFromFundingTx.compareTo(FeePolicy.getMinRequiredFeeForFundingTx()) >= 0);
                }*/
            }
        };

        offer.resetState();

        priceFeedService.setCurrencyCode(offer.getCurrencyCode());
    }

    // We don't want that the fee gets updated anymore after we show the funding screen.
    void onShowPayFundsScreen() {
        estimateTxSize();
        freezeFee = true;
        calculateTotalToPay();
    }

    void onTabSelected(boolean isSelected) {
        this.isTabSelected = isSelected;
        if (isTabSelected)
            priceFeedService.setCurrencyCode(offer.getCurrencyCode());
    }

    public void onClose() {
    	btcWalletService.resetAddressEntriesForOpenOffer(offer.getId());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI actions
    ///////////////////////////////////////////////////////////////////////////////////////////

    // errorMessageHandler is used only in the check availability phase. As soon we have a trade we write the error msg in the trade object as we want to
    // have it persisted as well.
    void onTakeOffer(TradeResultHandler tradeResultHandler) {
        checkNotNull(txFeeFromXmrFeeService, "txFeeFromXmrFeeService must not be null");
        checkNotNull(getTakerFee(), "takerFee must not be null");

        XmrCoin fundsNeededForTrade = getFundsNeededForTrade();
        if (isBuyOffer())
            fundsNeededForTrade = fundsNeededForTrade.add(amount.get());

        if (filterManager.isCurrencyBanned(offer.getCurrencyCode())) {
            new Popup<>().warning(Res.get("offerbook.warning.currencyBanned")).show();
        } else if (filterManager.isPaymentMethodBanned(offer.getPaymentMethod())) {
            new Popup<>().warning(Res.get("offerbook.warning.paymentMethodBanned")).show();
        } else if (filterManager.isOfferIdBanned(offer.getId())) {
            new Popup<>().warning(Res.get("offerbook.warning.offerBlocked")).show();
        } else if (filterManager.isNodeAddressBanned(offer.getMakerNodeAddress())) {
            new Popup<>().warning(Res.get("offerbook.warning.nodeBlocked")).show();
        } else if (filterManager.requireUpdateToNewVersionForTrading()) {
            new Popup<>().warning(Res.get("offerbook.warning.requireUpdateToNewVersion")).show();
        } else {
        	//TODO(niyid) TradeManager for XMR required
            tradeManager.onTakeOffer(XmrCoin.fromXmrCoin2Coin(amount.get(), "BSQ", bsqToXmrExchangeRate),
            		XmrCoin.fromXmrCoin2Coin(txFeeFromXmrFeeService, "BSQ", bsqToXmrExchangeRate),
            		XmrCoin.fromXmrCoin2Coin(getTakerFee(), "BSQ", bsqToXmrExchangeRate),
                    false,
                    tradePrice.getValue(),
                    XmrCoin.fromXmrCoin2Coin(fundsNeededForTrade, "BTC", btcToXmrExchangeRate),
                    offer,
                    paymentAccount.getId(),
                    false,
                    tradeResultHandler,
                    errorMessage -> {
                        log.warn(errorMessage);
                        new Popup<>().warning(errorMessage).show();
                    }
            );
        }
    }

    // This works only if have already funds in the wallet
    // TODO: There still are issues if we get funded by very small inputs which are causing higher tx fees and the
    // changed total required amount is not updated. That will cause a InsufficientMoneyException and the user need to
    // start over again. To reproduce keep adding 0.002 BTC amounts while in the funding screen.
    // It would require a listener on changed balance and a new fee estimation with a correct recalculation of the required funds.
    // Another edge case not handled correctly is: If there are many small inputs and user add a large input later the
    // fee estimation is based on the large tx with many inputs but the actual tx will get created with the large input, thus
    // leading to a smaller tx and too high fees. Simply updating the fee estimation would lead to changed required funds
    // and if funds get higher (if tx get larger) the user would get confused (adding small inputs would increase total required funds).
    // So that would require more thoughts how to deal with all those cases.
    public void estimateTxSize() {
        int txSize = 0;
        if (bsqWalletService.getAvailableConfirmedBalance().isPositive()) {
            XmrCoin fundsNeededForTrade = getFundsNeededForTrade();
            if (isBuyOffer())
                fundsNeededForTrade = fundsNeededForTrade.add(amount.get());

            // As taker we pay 3 times the fee and currently the fee is the same for all 3 txs (trade fee tx, deposit
            // tx and payout tx).
            // We should try to change that in future to have the deposit and payout tx with a fixed fee as the size is
            // there more deterministic.
            // The trade fee tx can be in the worst case very large if there are many inputs so if we take that tx alone
            // for the fee estimation we would overpay a lot.
            // On the other side if we have the best case of a 1 input tx fee tx then it is only 260 bytes but the
            // other 2 txs are larger (320 and 380 bytes) and would get a lower fee/byte as intended.
            // We apply following model to not overpay too much but be on the safe side as well.
            // We sum the taker fee tx and the deposit tx together as it can be assumed that both be in the same block and
            // as they are dependent txs the miner will pick both if the fee in total is good enough.
            // We make sure that the fee is sufficient to meet our intended fee/byte for the larger payout tx with 380 bytes.
            String btcToXmrExchangeRate = offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE);
            Tuple2<Coin, Integer> estimatedFeeAndTxSize = txFeeEstimationService.getEstimatedFeeAndTxSizeForTaker(XmrCoin.fromXmrCoin2Coin(fundsNeededForTrade, "BTC", btcToXmrExchangeRate),
            		XmrCoin.fromXmrCoin2Coin(getTakerFee(), "BTC", btcToXmrExchangeRate));
            txFeeFromXmrFeeService = XmrCoin.fromCoin2XmrCoin(estimatedFeeAndTxSize.first, "BTC", btcToXmrExchangeRate);
            feeTxSize = estimatedFeeAndTxSize.second;
        } else {
            feeTxSize = 380;
            txFeeFromXmrFeeService = txFeePerByteFromXmrFeeService.multiply(feeTxSize);
            log.info("We cannot do the fee estimation because there are no funds in the wallet.\nThis is expected " +
                            "if the user has not funded their wallet yet.\n" +
                            "In that case we use an estimated tx size of 380 bytes.\n" +
                            "txFee based on estimated size of {} bytes. feeTxSize = {} bytes. Actual tx size = {} bytes. TxFee is {} ({} sat/byte)",
                    feeTxSize, feeTxSize, txSize, txFeeFromXmrFeeService.toFriendlyString(), feeService.getTxFeePerByte());
        }
    }

    public void onPaymentAccountSelected(PaymentAccount paymentAccount) {
        if (paymentAccount != null) {
            this.paymentAccount = paymentAccount;

            long myLimit = getMaxTradeLimit();
            this.amount.set(XmrCoin.valueOf(Math.max(XmrCoin.fromCoin2XmrCoin(offer.getMinAmount(), "BTC", offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)).value, Math.min(amount.get().value, myLimit))));

            preferences.setTakeOfferSelectedPaymentAccountId(paymentAccount.getId());
        }
    }

    void fundFromSavingsWallet() {
        useSavingsWallet = true;
        updateBalance();
        if (!isXmrWalletFunded.get()) {
            this.useSavingsWallet = false;
            updateBalance();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    OfferPayload.Direction getDirection() {
        return offer.getDirection();
    }

    public Offer getOffer() {
        return offer;
    }

    ObservableList<PaymentAccount> getPossiblePaymentAccounts() {
        Set<PaymentAccount> paymentAccounts = user.getPaymentAccounts();
        checkNotNull(paymentAccounts, "paymentAccounts must not be null");
        return PaymentAccountUtil.getPossiblePaymentAccounts(offer, paymentAccounts, accountAgeWitnessService);
    }

    public PaymentAccount getLastSelectedPaymentAccount() {
        ObservableList<PaymentAccount> possiblePaymentAccounts = getPossiblePaymentAccounts();
        checkArgument(!possiblePaymentAccounts.isEmpty(), "possiblePaymentAccounts must not be empty");
        PaymentAccount firstItem = possiblePaymentAccounts.get(0);

        String id = preferences.getTakeOfferSelectedPaymentAccountId();
        if (id == null)
            return firstItem;

        return possiblePaymentAccounts.stream()
                .filter(e -> e.getId().equals(id))
                .findAny()
                .orElse(firstItem);
    }

    long getMaxTradeLimit() {
    	Coin coin = Coin.valueOf(getMaxTradeLimitBtc());
    	XmrCoin maxTradeAmount = XmrCoin.fromCoin2XmrCoin(coin, "BTC", String.valueOf(offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)));
        return maxTradeAmount.value;
    }

    long getMaxTradeLimitBtc() {
        if (paymentAccount != null) {
        	long maxTradeLimit = accountAgeWitnessService.getMyTradeLimit(paymentAccount, getCurrencyCode(),
                    offer.getMirroredDirection());
            return maxTradeLimit;
        } else {
            return 0;
        }
    }

    boolean canTakeOffer() {
        return GUIUtil.canCreateOrTakeOfferOrShowPopup(user, navigation) &&
                GUIUtil.isBootstrappedOrShowPopup(p2PService);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Bindings, listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void addListeners() {
        xmrWalletWrapper.addBalanceListener(balanceListener);
    }

    private void removeListeners() {
    	xmrWalletWrapper.removeBalanceListener(balanceListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////

    void calculateVolume() {
        if (tradePrice != null && offer != null &&
                amount.get() != null &&
                !amount.get().isZero()) {
            Volume volumeByAmount = tradePrice.getVolumeByAmount(Coin.valueOf(amount.get().value));
            if (offer.getPaymentMethod().getId().equals(PaymentMethod.HAL_CASH_ID))
                volumeByAmount = XmrOfferUtil.getAdjustedVolumeForHalCash(volumeByAmount);
            else if (CurrencyUtil.isFiatCurrency(getCurrencyCode()))
                volumeByAmount = XmrOfferUtil.getRoundedFiatVolume(volumeByAmount);

            volume.set(volumeByAmount);

            updateBalance();
        }
    }

    void applyAmount(XmrCoin amount) {
        this.amount.set(XmrCoin.valueOf(Math.min(amount.value, getMaxTradeLimit())));

        calculateTotalToPay();
    }

    void calculateTotalToPay() {
        // Taker pays 2 times the tx fee because the mining fee might be different when maker created the offer
        // and reserved his funds, so that would not work well with dynamic fees.
        // The mining fee for the takeOfferFee tx is deducted from the createOfferFee and not visible to the trader
        final XmrCoin takerFee = getTakerFee();
        if (offer != null && amount.get() != null && takerFee != null) {
            XmrCoin feeAndSecDeposit = getTotalTxFee().add(securityDeposit);
            if (isCurrencyForTakerFeeXmr()) {
                feeAndSecDeposit = feeAndSecDeposit.add(takerFee);
            }
            if (isBuyOffer())
                totalToPayAsCoin.set(feeAndSecDeposit.add(amount.get()));
            else
                totalToPayAsCoin.set(feeAndSecDeposit);

            updateBalance();
            log.debug("totalToPayAsCoin {}", totalToPayAsCoin.get().toFriendlyString());
        }
    }

    private boolean isBuyOffer() {
        return getDirection() == OfferPayload.Direction.BUY;
    }

    @Nullable
    XmrCoin getTakerFee(boolean isCurrencyForTakerFeeXmr) {
        XmrCoin amount = this.amount.get();
        if (amount != null) {
            // TODO write unit test for that
            XmrCoin feePerXmr = XmrCoinUtil.getFeePerXmr(XmrFeeService.getTakerFeePerXmr(isCurrencyForTakerFeeXmr, offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)), amount);
            return XmrCoinUtil.maxCoin(feePerXmr, XmrFeeService.getMinTakerFee(isCurrencyForTakerFeeXmr, offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)));
        } else {
            return null;
        }
    }

    @Nullable
    public XmrCoin getTakerFee() {
        return getTakerFee(isCurrencyForTakerFeeXmr());
    }

    public void swapTradeToSavings() {
        log.debug("swapTradeToSavings, offerId={}", offer.getId());
        btcWalletService.resetAddressEntriesForOpenOffer(offer.getId()); //TODO(niyid) Similar to create/edit offer, fees and wallet should be BSQ
    }

    // We use the sum of the size of the trade fee and the deposit tx to get an average.
    // Miners will take the trade fee tx if the total fee of both dependent txs are good enough.
    // With that we avoid that we overpay in case that the trade fee has many inputs and we would apply that fee for the
    // other 2 txs as well. We still might overpay a bit for the payout tx.
    private int getAverageSize(int txSize) {
        return (txSize + 320) / 2;
    }

    private XmrCoin getTxFeeBySize(int sizeInBytes) {
        return txFeePerByteFromXmrFeeService.multiply(getAverageSize(sizeInBytes));
    }

  /*  private void setFeeFromFundingTx(XmrCoin fee) {
        feeFromFundingTx = fee;
        isFeeFromFundingTxSufficient.set(feeFromFundingTx.compareTo(FeePolicy.getMinRequiredFeeForFundingTx()) >= 0);
    }*/

    boolean isMinAmountLessOrEqualAmount() {
        //noinspection SimplifiableIfStatement
        if (offer != null && amount.get() != null)
            return !offer.getMinAmount().isGreaterThan(XmrCoin.fromXmrCoin2Coin(amount.get(), "BTC", offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)));
        return true;
    }

    boolean isAmountLargerThanOfferAmount() {
        //noinspection SimplifiableIfStatement
        if (amount.get() != null && offer != null)
            return amount.get().isGreaterThan(XmrCoin.fromCoin2XmrCoin(offer.getAmount(), "BTC", btcToXmrExchangeRate));
        return true;
    }

    boolean wouldCreateDustForMaker() {
        //noinspection SimplifiableIfStatement
        boolean result;
        if (amount.get() != null && offer != null) {
        	double btcToXmrRate = xmrMarketPrice.getPrice();
            XmrCoin customAmount = XmrCoin.fromCoin2XmrCoin(offer.getAmount(), "BTC", offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE)).subtract(amount.get());
            result = customAmount.isPositive() && customAmount.isLessThan(XmrRestrictions.getMinNonDustOutput(btcToXmrRate));

            if (result)
                log.info("would create dust for maker, customAmount={},  XmrRestrictions.getMinNonDustOutput()={}", customAmount, XmrRestrictions.getMinNonDustOutput(btcToXmrRate));
        } else {
            result = true;
        }
        return result;
    }

    ReadOnlyObjectProperty<XmrCoin> getAmount() {
        return amount;
    }

    public PaymentMethod getPaymentMethod() {
        return offer.getPaymentMethod();
    }

    public String getCurrencyCode() {
        return offer.getCurrencyCode();
    }

    public String getCurrencyNameAndCode() {
        return CurrencyUtil.getNameByCode(offer.getCurrencyCode());
    }

    public XmrCoin getTotalTxFee() {
        XmrCoin totalTxFees = txFeeFromXmrFeeService.add(getTxFeeForDepositTx()).add(getTxFeeForPayoutTx());
        if (isCurrencyForTakerFeeXmr())
            return totalTxFees;
        else
            return totalTxFees.subtract(getTakerFee() != null ? getTakerFee() : XmrCoin.ZERO);
    }

    @NotNull
    private XmrCoin getFundsNeededForTrade() {
        return getSecurityDeposit().add(getTxFeeForDepositTx()).add(getTxFeeForPayoutTx());
    }

    private XmrCoin getTxFeeForDepositTx() {
        //TODO fix with new trade protocol!
        // Unfortunately we cannot change that to the correct fees as it would break backward compatibility
        // We still might find a way with offer version or app version checks so lets keep that commented out
        // code as that shows how it should be.
        return txFeeFromXmrFeeService; //feeService.getTxFee(320);
    }

    private XmrCoin getTxFeeForPayoutTx() {
        //TODO fix with new trade protocol!
        // Unfortunately we cannot change that to the correct fees as it would break backward compatibility
        // We still might find a way with offer version or app version checks so lets keep that commented out
        // code as that shows how it should be.
        return txFeeFromXmrFeeService; //feeService.getTxFee(380);
    }

    //TODO(niyid) How to find address???
    public String getAddressEntry() {
        return bsqWalletService.getUnusedBsqAddressAsString();//TODO(niyid) Handle this
    }

    public XmrCoin getSecurityDeposit() {
        return securityDeposit;
    }

    public Coin getBuyerSecurityDeposit() {
        return offer.getBuyerSecurityDeposit();
    }

    public Coin getSellerSecurityDeposit() {
        return offer.getSellerSecurityDeposit();
    }

    public Coin getBsqBalance() {
        return bsqWalletService.getAvailableConfirmedBalance();
    }

    public boolean isHalCashAccount() {
        return paymentAccount instanceof HalCashAccount;
    }

    public boolean isCurrencyForTakerFeeXmr() {
        return XmrOfferUtil.isCurrencyForTakerFeeXmr(preferences, bsqWalletService, amount.get(), offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE), offer.getExtraDataMap().get(OfferPayload.BSQ_TO_XMR_RATE));
    }

    public void setPreferredCurrencyForTakerFeeXmr(boolean isCurrencyForTakerFeeXmr) {
        preferences.setPayFeeInBtc(isCurrencyForTakerFeeXmr);
    }

    public boolean isPreferredFeeCurrencyXmr() {
        return preferences.isPayFeeInXmr();
    }

    public XmrCoin getTakerFeeInXmr() {
        return XmrOfferUtil.getTakerFee(true, amount.get(), offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE));
    }

    public Coin getTakerFeeInBsq() {
        return OfferUtil.getTakerFee(false, XmrCoin.fromXmrCoin2Coin(amount.get(), "BSQ", offer.getExtraDataMap().get(OfferPayload.BSQ_TO_XMR_RATE)));
    }

    boolean isTakerFeeValid() {
        return preferences.isPayFeeInXmr() || XmrOfferUtil.isBsqForTakerFeeAvailable(bsqWalletService, amount.get(), offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE), offer.getExtraDataMap().get(OfferPayload.BSQ_TO_XMR_RATE));
    }

    public boolean isBsqForFeeAvailable() {
        return XmrOfferUtil.isBsqForMakerFeeAvailable(bsqWalletService, amount.get(), offer.getExtraDataMap().get(OfferPayload.BTC_TO_XMR_RATE), offer.getExtraDataMap().get(OfferPayload.BSQ_TO_XMR_RATE));
    }
}