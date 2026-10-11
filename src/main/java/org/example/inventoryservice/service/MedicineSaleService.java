package org.example.inventoryservice.service;

import org.example.inventoryservice.entity.Medicine;
import org.example.inventoryservice.repository.MedicineRepository;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

@Service
public class MedicineSaleService {

    private final RedissonClient redissonClient;
    private final MedicineRepository medicineRepository;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;

    public MedicineSaleService(
            RedissonClient redissonClient,
            MedicineRepository medicineRepository,
            CacheManager cacheManager,
            PlatformTransactionManager transactionManager
    ) {
        this.redissonClient = redissonClient;
        this.medicineRepository = medicineRepository;
        this.cacheManager = cacheManager;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
    }

    public SaleResult sellMedicine(Long id) {

        // Mỗi loại thuốc có một Lock riêng
        String lockKey = "lock:medicine:" + id;

        RLock lock = redissonClient.getLock(lockKey);

        try {

            // Chờ tối đa 3 giây, giữ Lock tối đa 5 giây
            boolean isLocked = lock.tryLock(
                    3,
                    5,
                    TimeUnit.SECONDS
            );

            if (!isLocked) {
                return new SaleResult(
                        false,
                        "Không lấy được Lock, vui lòng thử lại"
                );
            }

            try {

                System.out.println(
                        Thread.currentThread().getName()
                                + " đã lấy Lock: " + lockKey
                );

                // Transaction hoàn thành trước khi mở Lock
                SaleResult result = transactionTemplate.execute(
                        status -> {

                            Medicine medicine = medicineRepository
                                    .findById(id)
                                    .orElse(null);

                            if (medicine == null) {
                                return new SaleResult(
                                        false,
                                        "Không tìm thấy thuốc ID: " + id
                                );
                            }

                            // Kiểm tra tồn kho khi đã giữ Lock
                            if (medicine.getStock() == null
                                    || medicine.getStock() <= 0) {

                                return new SaleResult(
                                        false,
                                        "Sản phẩm đã hết hàng!"
                                );
                            }

                            // Trừ đúng 1 sản phẩm
                            int updatedRows =
                                    medicineRepository.decreaseStock(
                                            id,
                                            1
                                    );

                            if (updatedRows == 0) {
                                return new SaleResult(
                                        false,
                                        "Sản phẩm đã hết hàng!"
                                );
                            }

                            System.out.println(
                                    "Đã bán 1 sản phẩm: "
                                            + medicine.getMedicineName()
                            );

                            return new SaleResult(
                                    true,
                                    "Thanh toán thành công thuốc: "
                                            + medicine.getMedicineName()
                            );
                        }
                );

                // Sau khi DB commit thành công, xóa cache thuốc
                if (result != null && result.success()) {

                    Cache cache = cacheManager.getCache("medicines");

                    if (cache != null) {
                        cache.evict(id);
                    }
                }

                return result;

            } finally {

                // Chỉ thread đang sở hữu Lock mới được unlock
                if (lock.isHeldByCurrentThread()) {

                    lock.unlock();

                    System.out.println(
                            "Đã giải phóng Lock: " + lockKey
                    );
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            return new SaleResult(
                    false,
                    "Yêu cầu bị gián đoạn khi chờ Lock"
            );
        }
    }

    public record SaleResult(
            boolean success,
            String message
    ) {
    }
}
