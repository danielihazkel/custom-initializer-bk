import type { Meta, StoryObj } from '@storybook/react';

const meta = {
  title: 'Example/Welcome',
  parameters: { layout: 'centered' },
  tags: ['autodocs'],
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Welcome: Story = {
  render: () => (
    <div style={{ fontFamily: 'system-ui', padding: '2rem', textAlign: 'center' }}>
      <h1>Storybook is set up</h1>
      <p>Add stories under <code>src/**/*.stories.tsx</code></p>
    </div>
  ),
};
