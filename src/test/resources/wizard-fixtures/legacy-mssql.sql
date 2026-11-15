-- T-SQL as SSMS scripts it: bracket-quoted names, filegroups, index options (WizardGoldenOutputTests).
CREATE TABLE [dbo].[Customers](
    [CustomerId] [int] IDENTITY(1,1) NOT NULL,
    [DisplayName] [nvarchar](200) NOT NULL,
    [ExternalRef] [uniqueidentifier] NULL,
    [Balance] [decimal](18, 2) NULL,
    [CreatedAt] [datetime2](7) NOT NULL,
    [IsActive] [bit] NOT NULL,
 CONSTRAINT [PK_Customers] PRIMARY KEY CLUSTERED ([CustomerId] ASC)
 WITH (PAD_INDEX = OFF, STATISTICS_NORECOMPUTE = OFF) ON [PRIMARY]
) ON [PRIMARY];

CREATE TABLE [dbo].[Invoices](
    [InvoiceId] [bigint] IDENTITY(1,1) NOT NULL,
    [CustomerId] [int] NOT NULL,
    [Total] [decimal](18, 2) NOT NULL,
    [IssuedOn] [date] NULL,
    [Notes] [nvarchar](max) NULL,
 CONSTRAINT [PK_Invoices] PRIMARY KEY CLUSTERED ([InvoiceId] ASC),
 CONSTRAINT [FK_Invoices_Customers] FOREIGN KEY ([CustomerId]) REFERENCES [dbo].[Customers] ([CustomerId])
) ON [PRIMARY];
