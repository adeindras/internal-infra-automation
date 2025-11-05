// Copyright (c) 2025 AccelByte Inc. All Rights Reserved.
// This is licensed software from AccelByte Inc, for limitations
// and restrictions contact your company contract manager.

package cache

import (
	"sync"
	"time"
)

type item[V any] struct {
	value  V
	expiry time.Time
}

func (i *item[V]) isExpired() bool {
	return time.Now().After(i.expiry)
}

type Cache[K comparable, V any] struct {
	items        map[K]item[V]
	mu           sync.Mutex
	globalExpiry time.Time
}

func New[K comparable, V any]() *Cache[K, V] {
	return &Cache[K, V]{
		items: make(map[K]item[V]),
	}
}

func (c *Cache[K, V]) IsExpired() bool {
	return time.Now().After(c.globalExpiry)
}

func (c *Cache[K, V]) Set(key K, value V, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[key] = item[V]{
		value:  value,
		expiry: time.Now().Add(ttl),
	}
	c.globalExpiry = time.Now().Add(ttl)
}

func (c *Cache[K, V]) Get(key K) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	i, ok := c.items[key]

	if !ok {
		return i.value, false
	}

	if c.IsExpired() {
		delete(c.items, key)
		return i.value, false
	}

	if i.isExpired() {
		delete(c.items, key)
		return i.value, false
	}

	return i.value, true
}

func (c *Cache[K, V]) Length() int {
	return len(c.items)
}

func (c *Cache[K, V]) Keys() []K {
	keys := make([]K, 0, len(c.items))
	for k := range c.items {
		keys = append(keys, k)
	}
	return keys
}
