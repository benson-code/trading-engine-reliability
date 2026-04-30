export interface Order {
  order_id:    string;
  type:        'BUY' | 'SELL';
  symbol:      string;
  amount:      string;
  price:       number;
  status:      string;
  timestamp:   number;
  thread_name: string;
}

export interface EngineStats {
  status:           'RUNNING' | 'STOPPED';
  total_generated:  number;
  unique_orders:    number;
  total_orders:     number;
  duplicate_count:  number;
  cache_size:       number;
  cache_hit_count:  number;
  cache_miss_count: number;
  cache_hit_rate:   number;
  has_duplicates:   boolean;
  buy_count:        number;
  sell_count:       number;
  last_price:       number;
}

export interface WsMessage {
  type: 'ORDER_CREATED' | 'STATS_UPDATE' | 'ENGINE_STATUS';
  data: Order | EngineStats;
}

export interface KLine {
  time:   number;   // Unix seconds (lightweight-charts format)
  open:   number;
  high:   number;
  low:    number;
  close:  number;
  volume: number;
}
