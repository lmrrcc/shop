package com.cherrytest.shop.domain.port;

import com.cherrytest.shop.domain.model.Order;
import com.cherrytest.shop.domain.model.OrderDraft;

public interface OrderRepository {
    Order place(OrderDraft draft);
}
