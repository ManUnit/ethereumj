/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.sharding.contract;

import org.ethereum.core.Block;
import org.ethereum.core.CallTransaction;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.crypto.HashUtil;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.listener.LogFilter;
import org.ethereum.listener.RecommendedGasPriceTracker;
import org.ethereum.sharding.domain.Validator;
import org.ethereum.util.FastByteComparisons;
import org.ethereum.util.Futures;
import org.ethereum.util.blockchain.EtherUtil;
import org.ethereum.vm.LogInfo;
import org.ethereum.vm.program.ProgramResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.ethereum.util.ByteUtil.longToBytes;
import static org.ethereum.util.blockchain.EtherUtil.convert;

/**
 * @author Mikhail Kalinin
 * @since 21.07.2018
 */
public class DepositContract {

    private static final Logger logger = LoggerFactory.getLogger("beacon");

    private static final byte[] DEPOSIT_WEI = convert(32, EtherUtil.Unit.ETHER).toByteArray();
    private static final long GAS_LIMIT = 100_000;
    private static final long DEFAULT_GAS_PRICE = convert(5, EtherUtil.Unit.GWEI).longValue();
    private static final long DEPOSIT_TIMEOUT = 5; // 5 minutes

    private byte[] address;
    private CallTransaction.Contract contract;
    private LogFilter depositFilter;

    @Autowired
    Ethereum ethereum;

    RecommendedGasPriceTracker gasPriceTracker;

    CompletableFuture<TransactionReceipt> depositFuture;
    byte[] depositTxHash;

    public DepositContract(Ethereum ethereum, byte[] address, String abi) {
        this.ethereum = ethereum;
        this.address = address;
        this.contract = new CallTransaction.Contract(abi);
        this.depositFilter = new LogFilter().withContractAddress(address)
                .withTopic(contract.getByName("Deposit").encodeSignatureLong());
    }

    public CompletableFuture<Validator> deposit(final byte[] pubKey, long withdrawalShard, byte[] withdrawalAddress,
                               byte[] randao, byte[] sender, Consumer<Transaction> sign) {

        byte[] data = contract.getByName("deposit").encode(pubKey, withdrawalShard, withdrawalAddress, randao);

        BigInteger nonce = ethereum.getRepository().getNonce(sender);
        long gasPrice = gasPriceTracker.getRecommendedGasPrice();
        Integer chainId = ethereum.getChainIdForNextBlock();
        Transaction tx = new Transaction(nonce.toByteArray(), longToBytes(gasPrice), longToBytes(GAS_LIMIT),
                address, DEPOSIT_WEI, data, chainId);
        sign.accept(tx);
        depositTxHash = tx.getHash();

        ethereum.addListener(new EthereumListenerAdapter() {
            @Override
            public void onPendingTransactionUpdate(TransactionReceipt txReceipt, PendingTransactionState state, Block block) {
                if (FastByteComparisons.equal(depositTxHash, txReceipt.getTransaction().getHash()) &&
                        state == PendingTransactionState.INCLUDED) {
                    depositFuture.complete(txReceipt);
                }
            }
        });

        depositFuture = new CompletableFuture<>();
        ethereum.submitTransaction(tx);
        logger.info("Register validator: {}, tx.hash: {}", HashUtil.shortHash(pubKey), Hex.toHexString(depositTxHash));

        return depositFuture.handleAsync(((receipt, t) -> {
            if (receipt == null)
                throw new RuntimeException(t);

            if (!receipt.isTxStatusOK())
                throw new RuntimeException(receipt.getError().isEmpty() ? "unknown" : receipt.getError());

            logger.info("Deposit Tx included in block, tx.hash: {}", Hex.toHexString(depositTxHash));

            // parse validator
            for (LogInfo log : receipt.getLogInfoList()) {
                if (isDepositLog(log)) {
                    Validator validator = Validator.fromLogData(log.getData());
                    if (validator != null && FastByteComparisons.equal(pubKey, validator.getPubKey()))
                        return validator;
                }
            }

            throw new RuntimeException("log.Deposit(" + Hex.toHexString(pubKey) + ") is not found in tx.hash: " +
                Hex.toHexString(receipt.getTransaction().getHash()));

        })).applyToEitherAsync(Futures.timeout(DEPOSIT_TIMEOUT, TimeUnit.MINUTES, "timeout exceeded"), (v) -> v);
    }

    public boolean isDepositLog(LogInfo log) {
        return depositFilter.matchesExactly(log);
    }

    public boolean usedPubKey(final byte[] pubKey) {
        Object[] args = new Object[] { pubKey };
        CallTransaction.Function usedPubKey = contract.getByName("used_pubkey");
        ProgramResult res = ethereum.callConstantFunction(Hex.toHexString(address), usedPubKey, args);
        return ((Boolean) usedPubKey.decodeResult(res.getHReturn())[0]);
    }

    @Autowired
    public void setEthereum(Ethereum ethereum) {
        this.ethereum = ethereum;
        this.ethereum.addListener(gasPriceTracker = new RecommendedGasPriceTracker() {
            @Override
            public Long getDefaultPrice() {
                return DEFAULT_GAS_PRICE;
            }
        });
    }
}
