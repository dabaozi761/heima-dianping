package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //互斥锁 解决缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        //1.从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁

        Shop shop = null;
        try {
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            //4.2判断是否获取锁
            if (!isLock) {
                //4.3失败，休眠
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //5.不存在，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY +id,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        //1.从redis中查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断是否是空值
        if (shopJson != null) {
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        //1.更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不存在");
        }
        updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        //2.删除缓存
        return Result.ok();
    }
}
