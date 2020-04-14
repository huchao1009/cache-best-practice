package com.opensource.cache;

import org.apache.commons.lang3.RandomUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DataObjectService {
    private final static String CACHE_KEY = "test:data:";
    private final static long ID_NOT_EXISTS = -1L;
    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * HashMap模拟数据库数据
     */
    private static Map<Long, DataObject> dbDataList = new HashMap<>();

    static {
        for (long i = 1; i <= 100; i++) {
            dbDataList.put(i, new DataObject(i, "name:" + i));
        }
    }

    public void addData(DataObject dataObject) {
        //第一步，保存到数据库
        this.saveToDB(dataObject);
        //第二步，保存到缓存
        setDataToCache(dataObject.getId(), dataObject);
    }

    public void deleteData(Long id) {
        //第一步，操作数据库
        deleteFromDB(id);
        //第二步，淘汰缓存
        try {
            deleteFromCache(id);
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }

    /**
     * 两个并发操作，操作时序如下：
     * 1、更新请求删除了缓存
     * 2、查询请求没有命中缓存
     * 3、查询请求从数据库中读出数据放入缓存
     * 4、更新请求更新了数据库中的数据。
     * 于是，在缓存中的数据还是老的数据，导致缓存中的数据是脏的
     *
     * @param dataObject
     */
    public void updateData1(DataObject dataObject) {
        //第一步，淘汰缓存
        deleteFromCache(dataObject.getId());
        //第二步，操作数据库
        updateFromDB(dataObject);
    }

    /**
     * 第一步数据库更新成功，第二步缓存操作失败，会导致缓存中的是脏数据
     *
     * @param dataObject
     */
    public void updateData2(DataObject dataObject) {
        //第一步，操作数据库
        updateFromDB(dataObject);
        //第二步，淘汰缓存
        deleteFromCache(dataObject.getId());
    }

    /**
     * 两个并发更新操作，操作时序
     * 1、请求1更新数据库
     * 2、请求2更新数据库
     * 3、请求2set缓存
     * 4、请求1set缓存
     * 数据库中的数据是请求2设置的，而缓存中的数据是请求1设置的，数据库与缓存的数据不一致
     *
     * @param dataObject
     */
    public void updateData3(DataObject dataObject) {
        //第一步，更新数据库
        updateFromDB(dataObject);
        //第二步，更新缓存
        setDataToCache(dataObject.getId(), dataObject);
    }

    /**
     * 将方法置于事务中执行，缓存操作失败抛出RuntimeException会回滚事务，保证了原子性
     * 缺点是redis远程操作会导致事务执行时间变长，降低并发
     * <p>
     * 两个并发操作，操作时序如下：
     * 1、查询请求没有命中缓存
     * 2、查询请求从数据库中读出数据
     * 3、更新请求更新了数据库
     * 4、更新请求删除缓存
     * 5、查询请求把读取到的老数据放入缓存
     * 于是，在缓存中的数据还是老的数据，导致缓存中的数据是脏的
     * <p>
     * 但，这个case理论上会出现，不过，实际上出现的概率可能非常低，
     * 因为这个条件需要发生在读缓存时缓存失效，而且并发着有一个写操作。
     * 而实际上数据库的写操作会比读操作慢得多，而且还要锁表，
     * 而读操作必需在写操作前进入数据库操作，而又要晚于写操作更新缓存，所有的这些条件都具备的概率基本并不大。
     *
     * @param dataObject
     */
//    @Transactional
    public void updateData(DataObject dataObject) {
        //第一步，操作数据库
        updateFromDB(dataObject);
        //第二步，淘汰缓存
        try {
            deleteFromCache(dataObject.getId());
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }

    /**
     * 缓存穿透、缓存击穿、缓存雪崩问题
     * @param id
     * @return
     */
    public DataObject getData1(Long id) {
        //从缓存读取数据
        DataObject result = getDataFromCache(id);
        if (result == null) {
            // 从数据库查询数据
            result = getDataFromDB(id);
            if (result != null) {
                // 将查询到的数据写入缓存
                setDataToCache(id, result);
            }
        }
        return result;
    }

    public DataObject getData(Long id) {
        //从缓存读取数据
        DataObject result = getDataFromCache(id);
        if (result == null) {
            //缓存不存在，从数据库查询数据的过程加上锁，避免缓存击穿导致数据库压力过大
            RLock lock = redissonClient.getLock("lock:" + CACHE_KEY + id);
            lock.lock(15, TimeUnit.SECONDS);
            if (lock.isLocked()) {
                try {
                    //双重判断,第二个以及之后的请求不必去找数据库,直接命中缓存
                    //再次查询缓存
                    result = getDataFromCache(id);
                    if (result == null) {
                        // 从数据库查询数据
                        result = getDataFromDB(id);
                        // 将查询到的数据写入缓存
                        setDataToCache(id, result);
                    }
                } finally {
                    //锁只能被拥有它的线程解锁
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        } else {
            if (result.getId() == ID_NOT_EXISTS) {
                return null;
            }
        }
        return result;
    }

    public Boolean deleteCache(Long id)
    {
        return this.deleteFromCache(id);
    }

    private void deleteFromDB(Long id) {
        dbDataList.remove(id);
        System.out.println("从数据库删除数据");
    }

    private void updateFromDB(DataObject dataObject) {
        dbDataList.put(dataObject.getId(),dataObject);
        System.out.println("从数据库修改数据");
    }

    private void saveToDB(DataObject dataObject) {
        dbDataList.put(dataObject.getId(), dataObject);
        System.out.println("将数据保存到数据库");
    }

    private DataObject getDataFromDB(Long id) {
        try {
            //睡眠100~800毫秒，模拟数据库IO慢操作
            TimeUnit.MILLISECONDS.sleep(RandomUtils.nextLong(100,800));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        DataObject dataObject = dbDataList.get(id);
        System.out.println("从【数据库】中获取数据, id: " + id + ", DataObject: " + ObjectUtils.nullSafeToString(dataObject));
        return dataObject;
    }

    private Boolean deleteFromCache(Long id) {
        System.out.println("从缓存中删除数据, id: " + id);
        return redisTemplate.delete(CACHE_KEY + id);
    }

    /**
     * 设置数据到缓存
     *
     * @param id
     * @param dataObject
     */
    private void setDataToCache(Long id, DataObject dataObject) {
        System.out.println("设置数据到【缓存】, DataObject: " + ObjectUtils.nullSafeToString(dataObject));
        if (dataObject != null) {
            //设置缓存过期时间时，加上一个随机值，避免同时过期导致缓存雪崩
            redisTemplate.opsForValue().set(CACHE_KEY + id, dataObject, 30 * 60 + (RandomUtils.nextInt(10, 300)), TimeUnit.SECONDS);
        } else {
            //设置特殊占位对象，并设置较短的过期时间，防止缓存穿透
            redisTemplate.opsForValue().set(CACHE_KEY + id, new DataObject(ID_NOT_EXISTS, null), 30 + (RandomUtils.nextInt(1, 10)), TimeUnit.SECONDS);
        }
    }

    /**
     * 从缓存中获取数据
     *
     * @param id
     * @return
     */
    private DataObject getDataFromCache(Long id) {
        ValueOperations<String, DataObject> valueOperations = redisTemplate.opsForValue();
        DataObject dataObject = valueOperations.get(CACHE_KEY + id);
        System.out.println("从【缓存】中获取数据, id: " + id + ", DataObject: " + ObjectUtils.nullSafeToString(dataObject));
        return dataObject;
    }

}
