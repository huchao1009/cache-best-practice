package com.opensource.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/data")
public class DataObjectController {

    @Autowired
    DataObjectService dataObjectService;

    /**
     * http://127.0.0.1:8080/data/updateData1?id=60&desc=new60
     * http://127.0.0.1:8080/data/getData?id=60
     * @param dataObject
     */
    @RequestMapping("/updateData1")
    public void updateData1(DataObject dataObject) {
        dataObjectService.updateData1(dataObject);
    }


    /**
     * 一、缓存穿透模拟：
     * 并发查询id 101~300 的请求
     * <p>
     * 二、缓存击穿模拟：
     * 1、查询ID为50的请求，这时id为50的数据会加载到缓存
     * 2、再次查询ID为50的请求，命中缓存
     * 3、将ID为50的缓存手动删除
     * 4、300并发请求ID为50的数据，这个时候请求全部走向数据库，ID为50的缓存被击穿
     * <p>
     * 三、缓存雪崩模拟：
     * 1、同时设置ID1~100的缓存
     * 2、查询1~100的数据，命中缓存
     * 3、手动删除ID 1~100 的缓存
     * 4、300并发随机请求ID 1~100 的数据
     *
     * @param id
     * @return
     */
    @RequestMapping("/getData1")
    public DataObject getData1(Long id) {
        return dataObjectService.getData1(id);
    }

    /**
     * 解决了缓存穿透、雪崩、击穿问题
     * @param id
     * @return
     */
    @RequestMapping("/getData")
    public DataObject getData(Long id) {
        return dataObjectService.getData(id);
    }

    /**
     * 手动删除缓存
     * @param id
     * @return
     */
    @RequestMapping("/deleteCache")
    public Boolean deleteCache(Long id) {
        return dataObjectService.deleteCache(id);
    }
}
