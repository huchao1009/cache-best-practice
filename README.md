### 缓存更新的策略

&nbsp;

##### 1、先淘汰缓存，再更新数据库

```java
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
```

```java
public void updateData1(DataObject dataObject) {
  //第一步，淘汰缓存
  deleteFromCache(dataObject.getId());
  //第二步，操作数据库
  updateFromDB(dataObject);
}
```

问题分析：

两个并发操作，操作时序如下：

- 1、更新请求删除了缓存

- 2、查询请求没有命中缓存

- 3、查询请求从数据库中读出数据放入缓存

- 4、更新请求更新了数据库中的数据。
  于是，在缓存中的数据还是老的数据，导致缓存中的数据是脏的

  &nbsp;



##### 2、先更新数据库，再修改缓存

```java
public void updateData3(DataObject dataObject) {
  //第一步，更新数据库
  updateFromDB(dataObject);
  //第二步，更新缓存
  setDataToCache(dataObject.getId(), dataObject);
}
```

问题分析：

两个并发更新操作，操作时序

- 1、请求1更新数据库

- 2、请求2更新数据库

- 3、请求2set缓存

- 4、请求1set缓存

  数据库中的数据是请求2设置的，而缓存中的数据是请求1设置的，数据库与缓存的数据不一致

  &nbsp;

  

##### 3、先更新数据库，再淘汰缓存

```java
public void updateData2(DataObject dataObject) {
  //第一步，操作数据库
  updateFromDB(dataObject);
  //第二步，淘汰缓存
  deleteFromCache(dataObject.getId());
}
```

这是经典的Cache Aside Pattern，这是标准的design pattern，包括Facebook的论文《Scaling Memcache at Facebook》也使用了这个策略。

问题分析：

第一步数据库更新成功，第二步缓存操作失败，会导致缓存中的是脏数据，原子性无法保证。

两个并发操作，操作时序如下：
- 1、查询请求没有命中缓存
- 2、查询请求从数据库中读出数据
- 3、更新请求更新了数据库
- 4、更新请求删除缓存
- 5、查询请求把读取到的老数据放入缓存
于是，在缓存中的数据还是老的数据，导致缓存中的数据是脏的

但这个case理论上会出现，不过实际上出现的概率可能非常低，
因为这个条件需要发生在读缓存时缓存失效，而且并发着有一个写操作。
而实际上数据库的写操作会比读操作慢得多，而且还要锁表，
而读操作必需在写操作前进入数据库操作，而又要晚于写操作更新缓存，所有的这些条件都具备的概率基本并不大。

&nbsp;



##### 4、先更新数据库，再淘汰缓存，并且保证原子性

```java
@Transactional    
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
```

问题分析：

这是Cache Aside Pattern的改进版，查询和更新请求并发的问题同样存在

将方法置于事务中执行，缓存操作失败抛出RuntimeException会回滚事务，保证了原子性
缺点是远程操作会导致事务执行时间变长，降低并发

&nbsp;

任何技术方案的设计，都是折衷。只有适合的方案，未必有最优的方案。

