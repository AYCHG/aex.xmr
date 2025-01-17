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

package bisq.desktop.main.portfolio.xmr.editoffer;

import bisq.desktop.Navigation;
import bisq.desktop.main.offer.xmr.XmrMutableOfferViewModel;
import bisq.desktop.util.validation.AltcoinValidator;
import bisq.desktop.util.validation.BsqValidator;
import bisq.desktop.util.validation.Xmr2Validator;
import bisq.desktop.util.validation.FiatPriceValidator;
import bisq.desktop.util.validation.FiatVolumeValidator;
import bisq.desktop.util.validation.SecurityDepositValidator;

import bisq.core.account.witness.AccountAgeWitnessService;
import bisq.core.offer.OpenOffer;
import bisq.core.provider.price.PriceFeedService;
import bisq.core.user.Preferences;
import bisq.core.util.BsqFormatter;
import bisq.core.util.XmrBSFormatter;
import bisq.core.xmr.wallet.XmrWalletRpcWrapper;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;

import com.google.inject.Inject;

class XmrEditOfferViewModel extends XmrMutableOfferViewModel<XmrEditOfferDataModel> {
    @Inject
    public XmrEditOfferViewModel(XmrEditOfferDataModel dataModel,
                              FiatVolumeValidator fiatVolumeValidator,
                              FiatPriceValidator fiatPriceValidator,
                              AltcoinValidator altcoinValidator,
                              Xmr2Validator xmrValidator,
                              BsqValidator bsqValidator,
                              SecurityDepositValidator securityDepositValidator,
                              PriceFeedService priceFeedService,
                              AccountAgeWitnessService accountAgeWitnessService,
                              Navigation navigation,
                              Preferences preferences,
                              XmrBSFormatter xmrFormatter,
                              BsqFormatter bsqFormatter,
                              XmrWalletRpcWrapper xmrWalletWrapper) {
        super(dataModel,
                fiatVolumeValidator,
                fiatPriceValidator,
                altcoinValidator,
                xmrValidator,
                bsqValidator,
                securityDepositValidator,
                priceFeedService,
                accountAgeWitnessService,
                navigation,
                preferences,
                xmrFormatter, 
                bsqFormatter,
                xmrWalletWrapper);
        syncMinAmountWithAmount = false;
    }

    @Override
    public void activate() {
        super.activate();
        dataModel.populateData();
    }

    public void applyOpenOffer(OpenOffer openOffer) {
        dataModel.reset();
        dataModel.applyOpenOffer(openOffer);
    }

    public void onStartEditOffer(ErrorMessageHandler errorMessageHandler) {
        dataModel.onStartEditOffer(errorMessageHandler);
    }

    public void onPublishOffer(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        dataModel.onPublishOffer(resultHandler, errorMessageHandler);
    }

    public void onCancelEditOffer(ErrorMessageHandler errorMessageHandler) {
        dataModel.onCancelEditOffer(errorMessageHandler);
    }

    public void onInvalidateMarketPriceMargin() {
        marketPriceMargin.set("0.00%");
        marketPriceMargin.set(XmrBSFormatter.formatToPercent(dataModel.getMarketPriceMargin()));
    }

    public void onInvalidatePrice() {
        price.set(XmrBSFormatter.formatPrice(null));
        price.set(XmrBSFormatter.formatPrice(dataModel.getPrice().get()));
    }

    public boolean isSecurityDepositValid() {
        return securityDepositValidator.validate(buyerSecurityDeposit.get()).isValid;
    }
}
