package com.devcamp.tripssoda.service;


import com.devcamp.tripssoda.dto.PaymentDetailDto;
import com.devcamp.tripssoda.dto.PaymentInitialInfoDto;

public interface PaymentService {
    public PaymentInitialInfoDto selectPaymentInitialInfo(Integer userId, Integer productId, Integer scheduleId);

    public String insertPayment(PaymentDetailDto paymentDetailDto) throws Exception;

    public boolean isValidPrice(PaymentDetailDto paymentDetailDto) throws Exception;

}
