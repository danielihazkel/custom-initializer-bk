import { createSlice, type PayloadAction } from '@reduxjs/toolkit';

interface CounterState {
  value: number;
}

const initialState: CounterState = { value: 0 };

/**
 * Sample slice. Delete or replace once you have real domain state — kept
 * minimal so the slice → store → useSelector pipeline is obvious.
 */
const counterSlice = createSlice({
  name: 'counter',
  initialState,
  reducers: {
    increment: (s) => { s.value += 1; },
    decrement: (s) => { s.value -= 1; },
    incrementBy: (s, action: PayloadAction<number>) => { s.value += action.payload; },
    reset: (s) => { s.value = 0; },
  },
});

export const { increment, decrement, incrementBy, reset } = counterSlice.actions;
export const counterReducer = counterSlice.reducer;
