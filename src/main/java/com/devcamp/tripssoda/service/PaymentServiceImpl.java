package com.devcamp.tripssoda.service;


import com.devcamp.tripssoda.dto.*;
import com.devcamp.tripssoda.exception.ExceedMaxMemberException;
import com.devcamp.tripssoda.exception.NotEnoughPointException;
import com.devcamp.tripssoda.exception.NotValidAmountException;
import com.devcamp.tripssoda.exception.InsertException;
import com.devcamp.tripssoda.mapper.PaymentMapper;
import com.devcamp.tripssoda.mapper.ProductMapper;
import com.devcamp.tripssoda.mapper.ReservationMapper;
import com.siot.IamportRestClient.IamportClient;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.request.CancelData;
import com.siot.IamportRestClient.response.IamportResponse;
import com.siot.IamportRestClient.response.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentMapper paymentMapper;
    private final UserPointService userPointService;
    private final IamportClient iamportClient;
    private final ReservationService reservationService;
    private final ProductMapper productMapper;

    public PaymentServiceImpl(PaymentMapper paymentMapper, UserPointService userPointService,
                              IamportClient iamportClient, ReservationService reservationService,
                              ProductMapper productMapper) {
        this.paymentMapper = paymentMapper;
        this.userPointService = userPointService;
        this.iamportClient = iamportClient;
        this.reservationService=reservationService;
        this.productMapper = productMapper;
    }

    @Override
    public PaymentInitialInfoDto selectPaymentInitialInfo(Integer userId, Integer productId, Integer scheduleId) {
        ReserverDto reserverDto = paymentMapper.selectReserverInfo(userId);

        Map<String, Integer> productInfo = new HashMap<>();
        productInfo.put("productId", productId);
        productInfo.put("scheduleId", scheduleId);

        PaymentInitialInfoDto paymentInitialInfoDto = paymentMapper.selectPaymentInitialInfo(productInfo);
        paymentInitialInfoDto.setReserver(reserverDto);
        return paymentInitialInfoDto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String insertPayment(PaymentDetailDto paymentDetailDto) throws Exception {

        //사용자 편의를 위한 placeholder 때문에 usedPoint 값이 null로 오는걸 방지하기 위함.
        //if (Objects.isNull(paymentDetailDto.getUsedPoint())) {
        //    paymentDetailDto.setUsedPoint(0);
        //}



        try {
            Map<String, Integer> scheduleInfo = new HashMap<>();
            scheduleInfo.put("userId", paymentDetailDto.getUserId());
            scheduleInfo.put("productId", paymentDetailDto.getProductId());
            scheduleInfo.put("scheduleId", paymentDetailDto.getScheduleId());


            int rowCnt = 0;
            rowCnt = productMapper.increaseCurrentMember(scheduleInfo);
            if(rowCnt != 1){
                throw new InsertException("Error occurred while increasing current member count");
            }
            rowCnt = productMapper.setUpdateBy(scheduleInfo);
            if(rowCnt != 1){
                throw new InsertException("Error occurred while updating system column");
            }
            //요청된 가격과 실제 서버에서의 가격이 동일한지 체크
            boolean validPrice = isValidPrice(paymentDetailDto);

            if (!validPrice) {
                throw new NotValidAmountException("Payment Amount is not valid");
            }

            Integer usedPoint = paymentDetailDto.getUsedPoint();
            Integer userId = paymentDetailDto.getUserId();

            //포인트가 사용됐을때만, 사용한 포인트 만큼 실제로 보유중인지 확인
            if(paymentDetailDto.getUsedPoint()>0) {
                int availablePoint = userPointService.isAvailablePoint(usedPoint, userId);
                if (availablePoint == -1) {
                    throw new NotEnoughPointException("Not enough user point");
                }

                //충분한 포인트 보유 중이면, 사용한 만큼 포인트 차감.
                Integer newPoint = availablePoint - usedPoint;
                rowCnt = userPointService.updateUserPoint(userId, newPoint, -usedPoint, "포인트 사용");
                if (rowCnt == -1) {
                    throw new InsertException("Error occurred while updating point");
                }
                System.out.println("BEFORE paymentDetailDto = " + paymentDetailDto);
            }
            rowCnt = paymentMapper.insertPayment(paymentDetailDto);
            System.out.println("AFTER paymentDetailDto = " + paymentDetailDto);

            if (rowCnt != 1){
                throw new InsertException("Error occurred while inserting payment detail");
            }

            rowCnt = reservationService.insertReservation(paymentDetailDto);
            if (rowCnt != 1){
                throw new InsertException("Error occurred while inserting reservation detail");
            }

            return "payment success";

        } catch (NotValidAmountException payAmount) {
            payAmount.printStackTrace();
            iamportCancelPayment(paymentDetailDto.getImpUid(), "요청결제가격과 실제가격 불일치");
            throw new Exception("code: P100");
        } catch (NotEnoughPointException userPoint) {
            userPoint.printStackTrace();
            iamportCancelPayment(paymentDetailDto.getImpUid(), "유저 포인트 부족");
            throw new Exception("code: P200");
        } catch (InsertException insert) {
            insert.printStackTrace();
            iamportCancelPayment(paymentDetailDto.getImpUid(), "서버 에러 발생으로 인한 취소");
            throw new Exception("code: P300");
        } catch (Exception e){
            e.printStackTrace();
            iamportCancelPayment(paymentDetailDto.getImpUid(), "알 수 없는 원인으로 인한 취소");
            throw new Exception();
        }
    }

    public void iamportCancelPayment(String impUid, String reason) throws IamportResponseException, IOException {
        iamportClient.cancelPaymentByImpUid(createCancelData(impUid, reason));
    }

    //return값이 0이면 available false, 1이면 available true
    public int isMemberAvailable(Map<String, Integer> scheduleInfo) {
        return productMapper.selectProductAvailability(scheduleInfo);
    }

    private CancelData createCancelData(String imp_uid, String reason) throws IamportResponseException, IOException {
        CancelData cancelData = new CancelData(imp_uid, true);
        cancelData.setReason(reason);
        return cancelData;
    }

    public boolean isValidPrice(PaymentDetailDto paymentDetailDto) throws NotValidAmountException {
        System.out.println("paymentDetailDto = " + paymentDetailDto);
        try {
            //imp_uuid를 통해 아임포트 서버에서 결제정보를 읽어온다
            IamportResponse<Payment> payment = iamportClient.paymentByImpUid(paymentDetailDto.getImpUid());
            BigDecimal totalAmount = payment.getResponse().getAmount();

            //사용자에서 요청한 결제 금액.
            Integer productQty = paymentDetailDto.getProductQty();
            Integer productAmount = paymentDetailDto.getProductAmount();
            Integer optionAmount = paymentDetailDto.getOptionAmount();
            Integer usedPointAmount = paymentDetailDto.getUsedPoint();
            Integer realTotalOptionPrice = 0;

            Map<String, Integer> productInfo = new HashMap<>();
            productInfo.put("productId", paymentDetailDto.getProductId());
            productInfo.put("scheduleId", paymentDetailDto.getScheduleId());

            //DB에서 읽어온 상품에 대한 실제 가격 정보.
            PriceProductDto priceFromDB = paymentMapper.selectPriceProduct(productInfo);
            System.out.println("priceFromDB = " + priceFromDB);
            System.out.println("priceFromDB.getPriceOptionList() = " + priceFromDB.getPriceOptionList());

            if (priceFromDB == null) {
                throw new Exception("Product info not found");
            }
            //상품 단가에 대한 가격 저장
            paymentDetailDto.setProductPrice(priceFromDB.getProductPrice());

            if (paymentDetailDto.getOptionDetail()!=null) {
                if (priceFromDB.getPriceOptionList().size() == 0) {
                    throw new Exception("Option info not found");
                }
                //DB에서 읽어온 옵션에 대한 실제 가격정보
                List<PriceOptionDto> priceOptFromDB = priceFromDB.getPriceOptionList();

                //optionId에 맞도록 Map에 옵션 내용과 가격정보를 담는다.
                Map<String, Map<String, String>> optPriceMap = new HashMap<>();
                for (int i = 0; i < priceOptFromDB.size(); i++) {
                    PriceOptionDto priceOptDto = priceOptFromDB.get(i);
                    String[] optContents = priceOptDto.getOptionContent().split(",");
                    String[] optPrices = priceOptDto.getOptionPrice().split(",");

                    Map<String, String> tmpOpt = new HashMap<>();
                    for (int j = 0; j < optContents.length; j++) {
                        tmpOpt.put(optContents[j], optPrices[j]);
                    }
                    optPriceMap.put(priceOptDto.getOptionId(), tmpOpt);
                }

                //사용자가 선택한 옵션 정보.
                Map<String, String> optDetail = paymentDetailDto.getOptionDetail();

                //DB에서 가져온 옵션 정보와 사용자가 선택한 옵션 정보를 비교
                //선택한 옵션들의 id와 내용들을 비교하여 실제 총 가격을 연산한다.
                for (int i = 0; i < optDetail.size(); i++) {
                    String[] detail = optDetail.get("" + i).split("#");
                    String optId = detail[0];
                    String optContent = detail[1];
                    optContent = optContent.contains("(단답형)") ? "X" : optContent;

                    Map<String, String> tmp = optPriceMap.get(optId);
                    Integer realPrice = Integer.parseInt(tmp.get(optContent));
                    realTotalOptionPrice += realPrice;
                }
            }


            Integer realTotalAmount = (priceFromDB.getProductPrice() * productQty) + realTotalOptionPrice - usedPointAmount;

            if (!productAmount.equals(priceFromDB.getProductPrice() * productQty)) {
                throw new NotValidAmountException("Product amount is not valid");
            } else if (!optionAmount.equals(realTotalOptionPrice)) {
                throw new NotValidAmountException("Option amount is not valid");
            }

            if (totalAmount.equals(BigDecimal.valueOf(realTotalAmount))) {
                return true;
            }

        } catch (IamportResponseException | IOException e) {
            e.printStackTrace();
        } catch (NotValidAmountException e) {
            e.printStackTrace();
            throw new NotValidAmountException("Payment amount is not correct");
        } catch (Exception e){
            e.printStackTrace();
            throw new NotValidAmountException("Payment amount validation failed");
        }
        return false;
    }

    public PaymentSuccessDto selectPaymentSuccessDetail(Map<String,  Integer> paymentInfo){
        return paymentMapper.selectPaymentSuccessDetail(paymentInfo);
    }

    public boolean increaseCurrentMember(Map<String, Integer> scheduleInfo){
        return true;
    }

}

