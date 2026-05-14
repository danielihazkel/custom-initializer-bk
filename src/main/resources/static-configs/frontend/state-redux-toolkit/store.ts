import { configureStore } from '@reduxjs/toolkit';
import { counterReducer } from '@entities/counter/model/counterSlice';

/**
 * Root Redux store. Add new reducers to the {@code reducer} map as you grow
 * the app — RTK enables Redux DevTools and the default middleware stack.
 */
export const store = configureStore({
  reducer: {
    counter: counterReducer,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
