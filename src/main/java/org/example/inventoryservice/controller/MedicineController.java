package org.example.inventoryservice.controller;

import org.example.inventoryservice.entity.Medicine;
import org.example.inventoryservice.service.InventoryService;
import org.example.inventoryservice.service.MedicineSaleService;
import org.example.inventoryservice.service.MedicineSaleService.SaleResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {

    private final InventoryService inventoryService;
    private final MedicineSaleService medicineSaleService;

    public MedicineController(
            InventoryService inventoryService,
            MedicineSaleService medicineSaleService
    ) {
        this.inventoryService = inventoryService;
        this.medicineSaleService = medicineSaleService;
    }

    // 1. Lấy thuốc - Redis Cache
    @GetMapping("/{id}")
    public Medicine getMedicineById(@PathVariable Long id) {
        return inventoryService.getMedicineById(id);
    }

    // 2. Cập nhật giá - Cache Evict
    @PutMapping("/{id}/price")
    public Medicine updatePrice(
            @PathVariable Long id,
            @RequestBody UpdatePriceRequest request
    ) {
        Medicine medicine = new Medicine();

        medicine.setId(id);
        medicine.setPrice(request.price());

        return inventoryService.updateMedicine(medicine);
    }

    public record UpdatePriceRequest(Double price) {
    }

    // 3. Bán 1 sản phẩm - Redis Distributed Lock
    @PutMapping("/sell/{id}")
    public ResponseEntity<SaleResult> sellMedicine(
            @PathVariable Long id
    ) {
        SaleResult result = medicineSaleService.sellMedicine(id);

        if (result.success()) {
            return ResponseEntity.ok(result);
        }

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(result);
    }

    // 4. API demo - Hai nhân viên bán cùng lúc
    @PutMapping("/sell/{id}/test-concurrent")
    public Map<String, SaleResult> testConcurrent(
            @PathVariable Long id
    ) throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<SaleResult> task = () -> {

            ready.countDown();

            // Hai thread cùng chờ tín hiệu bắt đầu
            start.await();

            return medicineSaleService.sellMedicine(id);
        };

        try {
            Future<SaleResult> first = executor.submit(task);
            Future<SaleResult> second = executor.submit(task);

            if (!ready.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Không khởi tạo đủ hai luồng"
                );
            }

            // Cho hai nhân viên bắt đầu cùng lúc
            start.countDown();

            SaleResult result1 = first.get(10, TimeUnit.SECONDS);
            SaleResult result2 = second.get(10, TimeUnit.SECONDS);

            System.out.println(
                    "Người dùng 1: " + result1.message()
            );

            System.out.println(
                    "Người dùng 2: " + result2.message()
            );

            return Map.of(
                    "nguoiDung1", result1,
                    "nguoiDung2", result2
            );

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
